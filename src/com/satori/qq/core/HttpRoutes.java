package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.net.HttpServer;
import com.satori.qq.net.HttpServer.HttpReq;
import com.satori.qq.net.HttpServer.HttpResult;
import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Multipart;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** HTTP 这一侧：鉴权、路由、请求体解析、资源文件的回读（含 Range），错误统一成 {code, message}。 */
final class HttpRoutes {
    private final Cfg cfg;
    private final QQClient qq;
    private final Identity identity;
    private final MsgStore store;
    private final Resources resources;
    private final OutboundGate gate;
    private final Dispatcher dispatcher;
    private final Monitor monitor;

    HttpRoutes(Cfg cfg, QQClient qq, Identity identity, MsgStore store, Resources resources, OutboundGate gate,
               Dispatcher dispatcher, Monitor monitor) {
        this.cfg = cfg;
        this.qq = qq;
        this.identity = identity;
        this.store = store;
        this.resources = resources;
        this.gate = gate;
        this.dispatcher = dispatcher;
        this.monitor = monitor;
    }

    /** upload.create 的请求体直接流到磁盘，不整份读进 QQ 进程的堆。 */
    boolean streams(String method, String path) {
        return "POST".equals(method) && "/v1/upload.create".equals(path);
    }

    HttpResult handle(HttpReq req) {
        try {
            String path = req.path == null ? "/" : req.path;
            boolean get = "GET".equals(req.method);
            if (get && path.startsWith("/v1/proxy/")) return proxy(path.substring("/v1/proxy/".length()), req);
            if (get && path.startsWith("/v1/internal/")) return internalResource(path, req);
            if (get && "/healthz".equals(path)) {
                return HttpResult.json(qq.isOnline() ? 200 : 503, monitor.healthz().toString());
            }
            if (!authorized(req)) {
                // 协议的状态码表：缺失鉴权 401，权限不足（令牌不对）403。
                return hasCredentials(req)
                        ? HttpResult.error(403, "invalid_token", "invalid token", null)
                        : HttpResult.error(401, "missing_token", "missing token", null);
            }
            if (get && "/".equals(path)) {
                return HttpResult.json(200, new JSONObject()
                        .put("name", SatoriHub.APP_NAME).put("version", SatoriHub.APP_VERSION)
                        .put("protocol", "v1").toString());
            }
            if (!"POST".equals(req.method)) {
                return path.startsWith("/v1/") ? HttpResult.error(405, "Please use POST") : HttpResult.error(404, "not found");
            }
            if (!path.startsWith("/v1/")) return HttpResult.error(404, "not found");
            String method = path.substring("/v1/".length());
            if ("meta".equals(method)) return json(meta());
            checkLoginHeaders(req);
            if ("upload.create".equals(method)) return json(upload(req));
            if (method.startsWith("internal/")) {
                return json(paginate(req, dispatcher.internal(method.substring("internal/".length()), internalBody(req))));
            }
            JSONObject body = body(req);
            if (OutboundGuard.isMutation(method)) return json(gate.guarded(method, () -> dispatcher.call(method, body)));
            return json(dispatcher.call(method, body));
        } catch (ApiError e) {
            Map<String, String> extra = e.retryAfter > 0 ? Map.of("Retry-After", String.valueOf(e.retryAfter)) : null;
            return HttpResult.error(e.http(), e.slug, e.getMessage(), extra);
        } catch (IllegalStateException e) {
            return HttpResult.error(500, e.getMessage());
        } catch (Throwable t) {
            L.e("http " + req.method + " " + req.path, t);
            return HttpResult.error(500, String.valueOf(t));
        }
    }

    private static HttpResult json(Object data) {
        return HttpResult.json(200, data == null ? "null" : data.toString());
    }

    private JSONObject meta() throws Exception {
        return new JSONObject()
                .put("logins", new JSONArray().put(identity.loginFull()))
                .put("proxy_urls", new JSONArray());
    }

    // ------------------------------------------------------------------ 鉴权

    /** 请求带了令牌（不论对错）：区分「缺失鉴权」与「令牌不对」。 */
    private static boolean hasCredentials(HttpReq req) {
        if (!req.header("authorization").isEmpty()) return true;
        for (String part : req.query.split("&")) {
            if (part.equals("access_token") || part.startsWith("access_token=")) return true;
        }
        return false;
    }

    private boolean authorized(HttpReq req) {
        if (cfg.token == null || cfg.token.isEmpty()) return true;
        String auth = req.header("authorization");
        if (auth.regionMatches(true, 0, "Bearer ", 0, 7) && cfg.token.equals(auth.substring(7).trim())) return true;
        for (String part : req.query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "access_token".equals(part.substring(0, eq)) && cfg.token.equals(part.substring(eq + 1))) {
                return true;
            }
        }
        return false;
    }

    /** A single-account implementation tolerates omitted selectors for compatibility, but never
     * routes a request explicitly addressed to another platform or login. */
    private void checkLoginHeaders(HttpReq req) {
        String platform = req.header("satori-platform");
        String userId = req.header("satori-user-id");
        if (!platform.isEmpty() && !Identity.PLATFORM.equals(platform)) {
            throw ApiError.loginNotFound("login not found: Satori-Platform " + platform);
        }
        if (Identity.isForeignLogin(userId, identity.selfUin())) {
            throw ApiError.loginNotFound("login not found: Satori-User-ID " + userId);
        }
    }

    // ------------------------------------------------------------------ 请求体

    private static JSONObject body(HttpReq req) {
        String text = req.bodyText();
        if (Json.blank(text)) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw ApiError.badRequest("malformed JSON request body");
        }
    }

    /**
     * Koishi's Satori internal proxy encodes method arguments as a JSON array, sent as JSON when
     * nothing is a blob and as {@code multipart/form-data} with the array under the field
     * {@code $} when something is. Both shapes arrive here.
     */
    private static JSONObject internalBody(HttpReq req) {
        String text = req.bodyText();
        String type = req.header("content-type");
        if (type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            List<Multipart.Part> parts = List.of();
            try {
                parts = Multipart.parseStream(new ByteArrayInputStream(req.body), type, Media.uploadDir(), 1024 * 1024);
                for (Multipart.Part part : parts) {
                    // Only the argument array matters here; the blobs next to it are not used by any action.
                    if ("$".equals(part.name)) text = new String(Files.readAllBytes(part.file.toPath()), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                throw ApiError.badRequest("malformed internal multipart body");
            } finally {
                for (Multipart.Part part : parts) if (part.file != null) part.file.delete();
            }
        }
        if (Json.blank(text)) return new JSONObject();
        String value = text.trim();
        try {
            if (!value.startsWith("[")) return new JSONObject(value);
            JSONArray args = new JSONArray(value);
            if (args.length() == 0) return new JSONObject();
            JSONObject first = args.optJSONObject(0);
            if (args.length() == 1 && first != null) return first;
            return new JSONObject().put("_args", args);
        } catch (Exception e) {
            throw ApiError.badRequest("malformed internal request body");
        }
    }

    /**
     * Honour {@code Satori-Pagination: true}, which the client sends when it is going to drive
     * {@code for await}. It only accepts an object with a {@code data} array, so wrap a bare array
     * (our list-shaped actions return one) and leave an already-paginated result alone. Actions
     * return everything in one page; the client then stops on the missing {@code next}.
     */
    private static Object paginate(HttpReq req, Object data) throws Exception {
        if (!"true".equalsIgnoreCase(req.header("satori-pagination").trim())) return data;
        if (data instanceof JSONArray rows) return new JSONObject().put("data", rows);
        if (data instanceof JSONObject o) {
            return o.optJSONArray("data") != null ? o : new JSONObject().put("data", new JSONArray().put(o));
        }
        return new JSONObject().put("data", new JSONArray());
    }

    // ------------------------------------------------------------------ upload.create

    private JSONObject upload(HttpReq req) throws Exception {
        String type = req.header("content-type");
        InputStream body = req.stream != null ? req.stream : new ByteArrayInputStream(req.body);
        List<Multipart.Part> parts;
        try {
            parts = Multipart.parseStream(body, type, Media.uploadDir(), HttpServer.MAX_UPLOAD_BYTES);
        } catch (Multipart.TooLarge e) {
            throw ApiError.tooLarge(e.getMessage() + "; a single upload.create file may be at most "
                    + HttpServer.MAX_UPLOAD_BYTES + " bytes");
        } catch (IllegalArgumentException e) {
            throw ApiError.badRequest(e.getMessage());
        }
        try {
            if (parts.isEmpty()) throw ApiError.badRequest("upload contains no files");
            Set<String> names = new HashSet<>();
            for (Multipart.Part part : parts) {
                if (part.name == null || part.name.isEmpty()) throw ApiError.badRequest("upload part missing name");
                if (!names.add(part.name)) throw ApiError.badRequest("duplicate upload name: " + part.name);
                if (part.contentType == null || part.contentType.isEmpty()) {
                    throw ApiError.badRequest("upload part missing Content-Type: " + part.name);
                }
            }
            JSONObject out = new JSONObject();
            for (Multipart.Part part : parts) {
                File file = Media.adoptUpload(part.file, part.filename, part.contentType);
                part.file = null;
                out.put(part.name, resources.publish(kindOf(part.contentType), file, part.filename));
            }
            return out;
        } finally {
            // Whatever was not adopted (a validation error part-way) must not linger on disk.
            for (Multipart.Part part : parts) if (part.file != null) part.file.delete();
        }
    }

    private static String kindOf(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) return "image";
        if (type.startsWith("audio/")) return "record";
        if (type.startsWith("video/")) return "video";
        return "file";
    }

    // ------------------------------------------------------------------ 资源回读

    private HttpResult asset(String rawId, HttpReq req) {
        if (rawId == null || rawId.isEmpty()) return HttpResult.error(400, "missing id");
        String id = rawId;
        int q = id.indexOf('?');
        if (q >= 0) id = id.substring(0, q);
        try {
            id = URLDecoder.decode(id, "UTF-8");
        } catch (Exception ignore) {
            // 解不开就按原样当 id 找
        }
        try {
            // This unauthenticated browser-facing route only serves opaque ids issued by us.
            // Direct paths remain accepted by internal/get_resource, never by HTTP GET.
            if (store.getResource(id) == null) return HttpResult.error(404, "not found");
            JSONObject res = resources.lookup(new JSONObject().put("file", id), null);
            String file = res.optString("file", "");
            File local = file.isEmpty() ? null : new File(file);
            if (local == null || !local.isFile()) return HttpResult.error(404, "not found");
            long size = local.length();
            byte[] head = new byte[32];
            int headLength;
            try (FileInputStream in = new FileInputStream(local)) {
                headLength = Math.max(0, in.read(head));
            }
            String type = mimeOf(headLength == head.length ? head : Arrays.copyOf(head, headLength),
                    local.getName(), res.optString("resource_type", ""));
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("Access-Control-Allow-Origin", "*");
            extra.put("Cache-Control", "private, max-age=3600");
            extra.put("Accept-Ranges", "bytes");
            long[] range = byteRange(req == null ? "" : req.header("range"), size);
            if (range != null && range[0] < 0) {
                extra.put("Content-Range", "bytes */" + size);
                return HttpResult.error(416, "range_not_satisfiable",
                        "requested range is outside the " + size + " byte resource", extra);
            }
            if (range != null) {
                extra.put("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + size);
                return HttpResult.file(206, type, local, range[0], range[1] - range[0] + 1, extra);
            }
            return HttpResult.file(200, type, local, 0, size, extra);
        } catch (ApiError e) {
            return HttpResult.error(e.code == 1404 ? 404 : 400, e.getMessage());
        } catch (Throwable t) {
            return HttpResult.error(500, String.valueOf(t));
        }
    }

    /**
     * A single {@code bytes=a-b} / {@code bytes=a-} / {@code bytes=-n} range as {@code {first, last}}
     * (inclusive). {@code null} means serve the whole thing (no header, a unit we do not know, or a
     * list of ranges — RFC 9110 lets a server ignore those); {@code {-1}} means unsatisfiable.
     */
    static long[] byteRange(String header, long size) {
        if (header == null) return null;
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6) || h.indexOf(',') >= 0) return null;
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String from = spec.substring(0, dash).trim(), to = spec.substring(dash + 1).trim();
            long first, last;
            if (from.isEmpty()) {
                if (to.isEmpty()) return null;
                long suffix = Long.parseLong(to);
                if (suffix <= 0) return new long[]{-1};
                first = Math.max(0, size - suffix);
                last = size - 1;
            } else {
                first = Long.parseLong(from);
                last = to.isEmpty() ? size - 1 : Math.min(Long.parseLong(to), size - 1);
            }
            if (first < 0 || first >= size || last < first) return new long[]{-1};
            return new long[]{first, last};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String mimeOf(byte[] head, String name, String kind) {
        String ext = Media.guessExt(head);
        String sniffed = ext == null ? null : switch (ext) {
            case ".png" -> "image/png";
            case ".jpg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".mp4" -> "video/mp4";
            case ".wav" -> "audio/wav";
            case ".mp3" -> "audio/mpeg";
            default -> null;
        };
        if (sniffed != null) return sniffed;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || "image".equals(kind)) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4") || "video".equals(kind)) return "video/mp4";
        if ("record".equals(kind) || n.endsWith(".silk") || n.endsWith(".amr")) return "audio/silk";
        return "application/octet-stream";
    }

    private HttpResult proxy(String encoded, HttpReq req) {
        if (encoded == null || encoded.isEmpty()) return HttpResult.error(400, "missing url");
        String url;
        try {
            url = URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception e) {
            return HttpResult.error(400, "invalid url");
        }
        if (url.startsWith("internal:")) {
            // internal:{platform}/{user.id}/{path}；格式不对 400，找不到登录 404。
            String[] parts = url.substring("internal:".length()).split("/", 3);
            if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                return HttpResult.error(400, "invalid internal url");
            }
            if (!Identity.PLATFORM.equals(parts[0]) || !String.valueOf(identity.selfUin()).equals(parts[1])) {
                return HttpResult.error(404, "login or resource not found");
            }
            if (!parts[2].startsWith("_tmp/")) return HttpResult.error(404, "unknown internal route");
            String id = parts[2].substring("_tmp/".length());
            if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0) {
                return HttpResult.error(400, "invalid internal url");
            }
            return asset(id, req);
        }
        if (!isHttpUrl(url)) return HttpResult.error(400, "invalid url");
        // 本实现不下载任何外链，proxy_urls 恒为空，所以合法的外链一律不在代理范围内。
        return HttpResult.error(403, "proxy url not allowed");
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * The Satori client's login-scoped internal route, {@code /v1/internal/{platform}/{selfId}/…}.
     *
     * <p>Resource ids that {@code upload.create} hands out are written as
     * {@code internal:{platform}/{selfId}/_tmp/{id}}. A client resolving one of those goes through
     * its own internal router and comes back here as a plain GET, so without this route the id we
     * just returned cannot be read. Received media uses the same links, so this and
     * {@code /v1/proxy/…} are the only resource routes. Local-only and unauthenticated: a browser
     * {@code <img>} cannot send a token.
     */
    private HttpResult internalResource(String path, HttpReq req) {
        String[] parts = path.substring("/v1/internal/".length()).split("/", 3);
        if (parts.length < 3 || parts[2].isEmpty()) return HttpResult.error(404, "not found");
        if (!Identity.PLATFORM.equals(parts[0]) || Identity.isForeignLogin(parts[1], identity.selfUin())) {
            return HttpResult.error(404, "login_not_found", "internal login not found", null);
        }
        // _api is POST-only; anything else under a login is not a resource we publish.
        if (parts[2].startsWith("_tmp/")) return asset(parts[2].substring("_tmp/".length()), req);
        return HttpResult.error(404, "not found");
    }
}
