package com.satori.qq.core;

/** 把客户端传来的松散 id 收成数：缺失、空串、乱码一律当 0，由调用方决定算不算错。 */
final class Ids {
    private Ids() {}

    static long parse(String raw) {
        try {
            return raw == null || raw.isEmpty() ? 0 : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** QQ 表情 id 是数字；Unicode 表情传的是字符本身，取它的码点。 */
    static long emoji(String raw) {
        if (raw == null || raw.isEmpty()) throw ApiError.badRequest("missing emoji_id");
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return raw.codePointAt(0);
        }
    }

    static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : b == null ? "" : b;
    }
}
