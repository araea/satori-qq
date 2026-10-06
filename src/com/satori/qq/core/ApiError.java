package com.satori.qq.core;

import com.satori.qq.qq.QQClient;

/**
 * 一次 API 调用的失败。{@code code} 是内部错误码：1400/1403/1404/1413 对应同号 HTTP 状态，
 * 1429 → 429，1503 → 503，其余 500。{@code slug} 进响应体的 {@code code}（缺省按状态取），
 * {@code retryAfter} 秒数大于 0 时带 {@code Retry-After}。
 */
final class ApiError extends RuntimeException {
    final int code;
    final String slug;
    final int retryAfter;

    private ApiError(int code, String slug, String message, int retryAfter) {
        super(message);
        this.code = code;
        this.slug = slug;
        this.retryAfter = retryAfter;
    }

    int http() {
        return switch (code) {
            case 1400 -> 400;
            case 1403 -> 403;
            case 1404 -> 404;
            case 1413 -> 413;
            case 1429 -> 429;
            case 1503 -> 503;
            default -> 500;
        };
    }

    /** 参数缺失或不合法。 */
    static ApiError badRequest(String message) { return new ApiError(1400, null, message, 0); }

    /** 请求点名的登录不是本实现端的登录。 */
    static ApiError loginNotFound(String message) { return new ApiError(1403, "login_not_found", message, 0); }

    /** 找不到被点名的对象。 */
    static ApiError notFound(String message) { return new ApiError(1404, null, message, 0); }

    /** 请求体超过限额。 */
    static ApiError tooLarge(String message) { return new ApiError(1413, "payload_too_large", message, 0); }

    /** 进了 QQ 内核之后失败。 */
    static ApiError failed(String message) { return new ApiError(1500, null, message, 0); }

    /** 群管类操作的统一收口：内核没答应就是失败。 */
    static void require(QQClient.OpResult result) {
        if (result == null || !result.ok()) {
            throw failed(result == null ? "group op failed" : result.describe());
        }
    }

    /** 限频或熔断：状态码 429/503，按 {@code retryAfter} 告诉客户端什么时候再来。 */
    static ApiError busy(boolean rateLimited, String slug, String message, int retryAfter) {
        return new ApiError(rateLimited ? 1429 : 1503, slug, message, retryAfter);
    }

    /** 内核离线：503，没有可承诺的恢复时间，所以不带 Retry-After。 */
    static ApiError kernelOffline() {
        return new ApiError(1503, "kernel_offline", "QQ kernel offline or not ready", 0);
    }

    /** 刚上线，等会话稳定：503 + Retry-After，请求没有交给 QQ，等够时间原样再发一次就行。 */
    static ApiError stabilizing(int seconds) {
        return new ApiError(1503, "session_stabilizing",
                "QQ session stabilizing; retry after " + seconds + "s", seconds);
    }

    /**
     * 404：这个方法在 QQ 上压根没有对应物。响应体仍是 JSON，和其他错误一样，客户端可以统一解析。
     */
    static ApiError unsupported(String method) {
        return new ApiError(1404, "unsupported_method", "API not found: " + method, 0);
    }

    /**
     * 曾经提供、后来移除的动作。
     *
     * <p>必须和「从来没这个方法」分开报：客户端要靠这个把能力标成不可用，而不是每轮重试
     * （acumen 的资料卡点赞就是这种用法——它按回执文案记住「平台不让做」，之后就不再调）。
     * 所以除了 404，响应体里还带 `code=removed_action`。
     */
    static ApiError removed(String method, String since, String why) {
        return new ApiError(1404, "removed_action", method + " 已移除（" + since + "起）：" + why, 0);
    }

    /**
     * 这次失败说不说明内核不健康？说不说明的，就不该开熔断。
     *
     * <p>两类：
     * <ul>
     *   <li>富媒体上传失败——调用方的下一个动作通常是同一连接上的纯文本兜底，那一条往往还能过；
     *       把它算进熔断，等于把「降级但可用」变成持续 {@code circuitOpenMs} 的全断。</li>
     *   <li>**调用方参数就不对**（1400／1404）。那是我们自己在进内核之前回绝的，跟内核健不健康没有
     *       关系。不排除的话，任何客户端连发三个缺参请求就能把出站通道锁两分钟
     *       （2026-09-19 实测：巡检里的缺参用例正好把熔断打开了，后面十几项全被拒）。</li>
     * </ul>
     */
    static boolean isKernelNeutral(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof ApiError e && (e.code == 1400 || e.code == 1404)) return true;
            String m = c.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("rich media") || lower.contains("media transfer") || lower.contains("富媒体")) {
                return true;
            }
        }
        return false;
    }
}
