package com.satori.qq.core;

import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import java.io.File;
import org.json.JSONObject;

/**
 * 资源 id 与本地文件之间的换算。收到的媒体、{@code upload.create} 的产物、截图都以
 * {@code internal:red/<uin>/_tmp/<id>} 链接对外；这里把链接、id 与磁盘上的文件对上。
 */
final class Resources {
    private final QQClient qq;
    private final MsgStore store;
    private final Identity identity;

    Resources(QQClient qq, MsgStore store, Identity identity) {
        this.qq = qq;
        this.store = store;
        this.identity = identity;
    }

    /** 登记一个已落盘的文件，返回对外的 {@code internal:} 链接。 */
    String publish(String kind, File file, String filename) {
        String id = store.putResource(kind, "", file.getAbsolutePath(), "", filename, file.length());
        return identity.assetBase() + id;
    }

    /** 把 {@code internal:} 链接解回本地文件路径；链接属于别的登录或文件已不在，按 404 回。 */
    String resolveLink(String src) throws Exception {
        String prefix = identity.assetBase();
        if (!src.startsWith(prefix)) throw ApiError.notFound("internal resource login not found");
        String file = lookup(new JSONObject().put("file", src.substring(prefix.length())), null).optString("file", "");
        if (file.isEmpty()) throw ApiError.notFound("internal resource not found");
        return file;
    }

    /** 头像一类的入参：本地路径、file:、data:、http(s)、base64 或 internal: 链接。 */
    File resolveImage(String spec) throws Exception {
        String value = spec == null ? "" : spec.trim();
        if (value.isEmpty()) throw ApiError.badRequest("missing avatar");
        if (value.startsWith("internal:")) value = resolveLink(value);
        File local = Media.resolve(value, value);
        if (local == null || !local.isFile()) throw ApiError.badRequest("cannot resolve avatar");
        return local;
    }

    /** Resolve an opaque file id learned from an incoming segment to a local path and/or source URL. */
    JSONObject lookup(JSONObject p, String expectedType) throws Exception {
        String id = p.optString("file", p.optString("file_id", p.optString("id", ""))).trim();
        if (id.isEmpty()) throw ApiError.badRequest("missing file/file_id");
        MsgStore.Resource resource = store.getResource(id);
        boolean registered = resource != null;
        if (resource == null) {
            // Also accept a direct local path or URL for compatibility with clients that retain segment data.
            resource = new MsgStore.Resource();
            resource.id = id;
            resource.type = expectedType == null ? "file" : expectedType;
            if (id.startsWith("http://") || id.startsWith("https://")) resource.url = id;
            else resource.path = id.startsWith("file://") ? id.substring(7) : id;
        }
        if (expectedType != null && resource.type != null && !expectedType.equals(resource.type)) {
            throw ApiError.badRequest("resource type is " + resource.type + ", expected " + expectedType);
        }

        // Prefer an existing local file, then QQ's authenticated kernel downloader. Old qpic URLs
        // frequently expire or stall, so direct HTTP is deliberately the final fallback.
        File local = Media.resolve(resource.path, "");
        if (local == null && resource.msgId != 0 && qq.isOnline()) {
            String downloaded = qq.downloadRichMedia(resource.chatType, resource.peerUid, resource.msgId,
                    resource.elementId, resource.fileModelId);
            if (!downloaded.isEmpty()) {
                local = new File(downloaded);
                resource.path = downloaded;
                resource.size = local.length();
            }
        }
        if (local == null && "video".equals(resource.type) && resource.msgId != 0 && qq.isOnline()) {
            String play = qq.getVideoPlayUrl(resource.chatType, resource.peerUid, resource.msgId, resource.elementId);
            if (!play.isEmpty()) {
                resource.url = play;
                local = Media.resolve("", play);
                if (local != null) resource.size = local.length();
            }
        }
        boolean hasUrl = resource.url != null && !resource.url.isEmpty();
        if (local == null && hasUrl) local = Media.resolve("", resource.url);
        if (local == null && !hasUrl) {
            String why = !registered ? "unregistered" : resource.msgId == 0 ? "no download context" : "download failed";
            throw ApiError.notFound("resource unavailable (" + why + ")");
        }

        JSONObject out = new JSONObject()
                .put("resource_id", resource.id)
                .put("resource_type", resource.type == null ? "file" : resource.type)
                .put("file_name", resource.name == null ? "" : resource.name)
                .put("file_size", local != null ? local.length() : resource.size);
        if (local != null && "record".equals(expectedType)) {
            String format = p.optString("out_format", p.optString("outFormat", "")).trim();
            if (!format.isEmpty()) {
                File converted = Media.convertRecord(qq.ref, local, format);
                if (converted == null) throw ApiError.badRequest("unsupported or failed out_format: " + format);
                local = converted;
                String produced = converted.getName();
                int dot = produced.lastIndexOf('.');
                out.put("out_format", dot >= 0 ? produced.substring(dot + 1) : format);
                out.put("file_size", local.length());
            }
        }
        out.put("file", local != null ? local.getAbsolutePath() : resource.url);
        if (hasUrl) out.put("url", resource.url);
        return out;
    }
}
