package com.satori.qq.core;

import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * 资源 id 与本地文件之间的换算。收到的媒体、{@code upload.create} 的产物、截图都以
 * {@code internal:red/<uin>/_tmp/<id>} 链接对外；这里把链接、id 与磁盘上的文件对上。
 */
final class Resources {
    /** 图片预取的客户端通常等 30 秒；留 5 秒给 HTTP 回包，别让两条下载路径串行撞线。 */
    private static final long IMAGE_FETCH_TIMEOUT_MS = 25_000;
    private static final ExecutorService IMAGE_FETCHES = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "satori-image-fetch");
        thread.setDaemon(true);
        return thread;
    });

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

        // Prefer an existing local file. A received image has two independent sources: the fresh
        // qpic URL and QQ's authenticated kernel downloader. Fetch both concurrently: putting the
        // kernel's 30-second wait first used to consume the caller's whole timeout before the URL
        // fallback even started. Other media keep the original sequential policy.
        File local = Media.resolve(resource.path, "");
        if (local == null && "image".equals(resource.type)) {
            local = fetchImage(resource);
            if (local != null) {
                resource.path = local.getAbsolutePath();
                resource.size = local.length();
            }
        }
        if (local == null && !"image".equals(resource.type) && resource.msgId != 0 && qq.isOnline()) {
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
        if (local == null && hasUrl && !"image".equals(resource.type)) local = Media.resolve("", resource.url);
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

    /**
     * Race the public image URL against QQ's message-scoped downloader. The first non-empty file
     * wins; if one source fails, the other still has the rest of the shared 25-second budget.
     * Cancelling the loser also interrupts a kernel latch wait. A native download already started
     * by QQ may finish in the background, which is harmless and can populate QQ's own cache.
     */
    private File fetchImage(MsgStore.Resource resource) {
        String url = resource.url == null ? "" : resource.url;
        boolean kernel = resource.msgId != 0 && qq.isOnline();
        if (url.isEmpty() && !kernel) return null;

        CompletableFuture<File> first = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger((url.isEmpty() ? 0 : 1) + (kernel ? 1 : 0));
        List<Future<?>> tasks = new ArrayList<>(2);
        if (!url.isEmpty()) {
            tasks.add(IMAGE_FETCHES.submit(() -> {
                try {
                    File file = Media.resolve("", url);
                    if (usable(file)) first.complete(file);
                } finally {
                    if (remaining.decrementAndGet() == 0) first.complete(null);
                }
            }));
        }
        if (kernel) {
            tasks.add(IMAGE_FETCHES.submit(() -> {
                try {
                    String path = qq.downloadRichMedia(resource.chatType, resource.peerUid, resource.msgId,
                            resource.elementId, resource.fileModelId);
                    File file = path.isEmpty() ? null : new File(path);
                    if (usable(file)) first.complete(file);
                } finally {
                    if (remaining.decrementAndGet() == 0) first.complete(null);
                }
            }));
        }
        try {
            File file = first.get(IMAGE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return usable(file) ? file : null;
        } catch (TimeoutException | ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            for (Future<?> task : tasks) task.cancel(true);
        }
    }

    private static boolean usable(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }
}
