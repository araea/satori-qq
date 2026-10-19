package com.satori.qq.update;

/** 这个应用的更新来源与要更新的东西。换一个项目只改这一个文件。 */
final class Source {
    private Source() {}

    static final String LABEL = "知弦";
    static final String PACKAGE = "com.satori.qq";
    static final String LAUNCH = PACKAGE + "/.ui.MainActivity";
    static final String MODULE_ID = "satori_qq";

    /** 发布页附带的清单；latest/download 是 GitHub 的固定跳转，不计 API 的访问配额。 */
    static final String MANIFEST = "https://github.com/araea/satori-qq/releases/latest/download/update.json";
    static final String PAGE = "https://github.com/araea/satori-qq/releases/latest";

    /** 导出到「下载」目录时的文件名：带版本号，一眼认得出是哪一版。 */
    static String moduleFile(String version) {
        return "SatoriQQ-module-" + version + ".zip";
    }

    static String appFile(String version) {
        return "SatoriQQ-" + version + ".apk";
    }
}
