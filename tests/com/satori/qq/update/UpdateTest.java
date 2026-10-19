package com.satori.qq.update;

import com.satori.qq.ui.Status;
import com.satori.qq.ui.UpdateFaces;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 更新的判断逻辑：发布清单的解析与拒收、版本比较、"现状 × 线上最新 → 要做什么"、
 * 模块状态的探测输出、更新说明的纯文本化，以及卡片上的文案与按钮。
 *
 * <p>钉住的底线：清单里任何一处不对就整份拒收；没有 root、服务也不在线时不下任何"要更新"的结论；
 * 模块已暂存（等重启）时不再提示更新，而是提示重启。
 */
public final class UpdateTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] args) throws Exception {
        releases();
        versions();
        plans();
        probe();
        notes();
        faces();
    }

    private static JSONObject manifest() throws JSONException {
        return new JSONObject()
                .put("tag", "154-0.34.0").put("notes", "## 新增\n- 更新").put("page", "https://github.com/a/b/releases/tag/154-0.34.0")
                .put("version", "0.34.0").put("versionCode", 154).put("zipUrl", "https://github.com/a/b/releases/download/154-0.34.0/m.zip")
                .put("sha256", SHA).put("size", 1000)
                .put("app", new JSONObject().put("version", "0.34.0").put("versionCode", 154)
                        .put("url", "https://github.com/a/b/releases/download/154-0.34.0/a.apk").put("sha256", SHA.toUpperCase()).put("size", 2000));
    }

    private static void releases() throws Exception {
        Release r = Release.parse(manifest().toString());
        eq("154-0.34.0", r.tag, "tag");
        eq(154L, r.module.code, "模块版本号");
        eq(SHA, r.app.sha256, "校验值统一小写");
        eq("0.34.0", r.headline(), "展示版本");

        JSONObject onlyApp = manifest();
        onlyApp.remove("zipUrl");
        check(Release.parse(onlyApp.toString()).module == null, "没有 zipUrl 就是没附模块");
        JSONObject noApp = manifest();
        noApp.remove("app");
        check(Release.parse(noApp.toString()).app == null, "没有 app 就是没附应用");

        rejects(manifest().put("zipUrl", "http://example.com/m.zip"), "明文 http 下载地址");
        rejects(manifest().put("zipUrl", "ftp://example.com/m.zip"), "非 https 下载地址");
        rejects(manifest().put("sha256", "abc"), "校验值格式不对");
        rejects(manifest().put("size", 0), "大小为 0");
        rejects(manifest().put("size", 1L << 40), "大小离谱");
        rejects(manifest().put("versionCode", 0), "版本号为 0");
        rejects(manifest().put("tag", " "), "tag 为空");
        rejects(manifest().put("page", "javascript:alert(1)"), "page 不是 https");
        check(Release.parse(manifest().put("zipUrl", "http://127.0.0.1:8080/m.zip").toString()).module != null, "回环地址放行（本机验收）");
        try {
            Release.parse("not json");
            throw new AssertionError("非 JSON 应被拒收");
        } catch (JSONException expected) {
            // 预期。
        }
    }

    private static void rejects(JSONObject json, String what) {
        try {
            Release.parse(json.toString());
        } catch (JSONException expected) {
            return;
        }
        throw new AssertionError("应当拒收：" + what);
    }

    private static void versions() {
        eq(-1, Versions.compare("0.33.2", "0.34.0"), "小于");
        eq(1, Versions.compare("0.34.0", "0.33.9"), "大于");
        eq(0, Versions.compare("v0.18.1", "0.18.1"), "忽略 v");
        eq(1, Versions.compare("0.10.0", "0.9.9"), "按数字而不是按字典序");
        eq(0, Versions.compare("1.2", "1.2.0"), "缺位当 0");
        eq(0, Versions.compare("0.33.2-debug", "0.33.2"), "忽略后缀");
        eq(-1, Versions.compare("", "0.1"), "空串最小");
    }

    private static ModuleState module(String out) {
        return ModuleState.parse(out);
    }

    private static void plans() throws Exception {
        Release r = Release.parse(manifest().toString());
        ModuleState same = module("present=1\nversion=0.33.2\ncode=153\n");
        ModuleState current = module("present=1\nversion=0.34.0\ncode=154\n");

        Plan p = Plan.of(r, 153, same, "0.33.2");
        check(p.app && p.module && !p.missing && !p.reboot, "应用与模块都落后");
        p = Plan.of(r, 154, same, "0.33.2");
        check(!p.app && p.module, "只有模块落后");
        p = Plan.of(r, 154, current, "0.34.0");
        check(!p.any() && !p.reboot, "都是最新");
        p = Plan.of(r, 155, current, "0.34.0");
        check(!p.app, "本机比线上新（开发版）不提示");

        // 模块已暂存：不再提示更新，改为提示重启
        ModuleState staged = module("present=1\nstaged=1\nversion=0.34.0\ncode=154\nstaged_version=0.34.0\nstaged_code=154\n");
        p = Plan.of(r, 154, staged, "0.33.2");
        check(!p.module && p.reboot, "已暂存：不再更新，等重启");
        // 磁盘上的比运行中的新（没有暂存标记，例如手工覆盖过）
        p = Plan.of(r, 154, current, "0.33.2");
        check(p.reboot, "磁盘版本比运行中的新：等重启");
        p = Plan.of(r, 154, current, "");
        check(!p.reboot, "服务不在线：不下结论");

        // 全新安装：暂存的模块 + 占位
        ModuleState fresh = module("present=1\nstaged=1\nversion=0.34.0\ncode=154\nstaged_version=0.34.0\nstaged_code=154\n");
        p = Plan.of(r, 154, fresh, "");
        check(!p.module && p.reboot, "新装已暂存：等重启");

        // 没装模块（拿到了 root）
        p = Plan.of(r, 154, module(""), "");
        check(p.module && p.missing, "没装模块 → 提示安装");
        p = Plan.of(r, 154, module("present=1\nremoving=1\nversion=0.33.2\ncode=153\n"), "");
        check(p.module && p.missing, "标记为卸载的按没装算");

        // 没有 root：只能看服务报上来的版本
        p = Plan.of(r, 153, ModuleState.UNKNOWN, "0.33.2");
        check(p.module && !p.missing && !p.reboot, "无 root：用运行版本比较");
        p = Plan.of(r, 154, ModuleState.UNKNOWN, "");
        check(!p.module && !p.missing, "无 root 且服务不在线：不下结论");

        // 还没检查过
        p = Plan.of(null, 153, current, "0.33.2");
        check(!p.any() && p.reboot, "没有线上信息时只可能提示重启");
    }

    private static void probe() {
        ModuleState s = module("present=1\ndisabled=1\nstaged=1\nversion=0.33.2\ncode=153\nstaged_version=0.34.0\nstaged_code=154\ncli=/data/adb/ksud\n");
        check(s.known && s.present && s.disabled && s.staged, "标记");
        eq(154L, s.effectiveCode(), "暂存的版本号优先");
        eq("0.34.0", s.effectiveVersion(), "暂存的版本名优先");
        eq("/data/adb/ksud", s.cli, "命令路径");
        check(module("garbage line\nx=\n").known, "探测成功但什么也没有 = 没装");
        check(!module("").present, "没有 present 行 = 没装");
        eq("'/data/adb/ksud' module install '/z.zip'", ModuleState.installCommand("/data/adb/ksud", "/z.zip"), "KernelSU 命令");
        eq("'/data/adb/ap/bin/apd' module install '/z.zip'", ModuleState.installCommand("/data/adb/ap/bin/apd", "/z.zip"), "APatch 命令");
        eq("'magisk' --install-module '/z.zip'", ModuleState.installCommand("magisk", "/z.zip"), "Magisk 命令");
        check(ModuleState.probeScript("satori_qq").contains("/data/adb/modules_update/satori_qq"), "探测脚本带模块 id");
    }

    private static void notes() {
        eq("新增\n\n• 更新项\n• 带链接 文字", Notes.plain("## 新增\n\n- **更新**项\n- 带链接 [文字](http://x)\n"), "标记去掉");
        eq("", Notes.plain("\n\n"), "空说明");
        eq("a\n\nb", Notes.plain("a\n\n\n\nb"), "连续空行折成一行");
    }

    private static void faces() throws Exception {
        Release r = Release.parse(manifest().toString());
        ModuleState same = module("present=1\nversion=0.33.2\ncode=153\n");
        List<Updater.Export> none = Collections.emptyList();

        Updater.State idle = state(r, Plan.of(r, 153, same, "0.33.2"), same, false, 1000, "", "", "", none);
        UpdateFaces.Face face = UpdateFaces.update(idle, "知弦", "0.33.2");
        check(face != null && face.primaryAction == UpdateFaces.UPDATE && "更新".equals(face.primary), "有新版本：主按钮是更新");
        check(face.title.contains("0.34.0") && face.body.contains("0.33.2") && face.body.contains("重启"), "写明版本与重启");
        eq(UpdateFaces.NOTES, face.secondaryAction, "有说明时给「更新内容」");
        check(UpdateFaces.reboot(idle, "知弦", "0.33.2") == null, "没装好就没有重启卡");

        Updater.State working = state(r, idle.plan, same, false, 1000, "", "正在下载应用 0.34.0…", "", none);
        face = UpdateFaces.update(working, "知弦", "0.33.2");
        check(face.busy && face.primary == null && face.body.contains("正在下载"), "更新中：转圈、无按钮");
        check(UpdateFaces.row(working, "0.33.2", 2000).contains("正在更新"), "行说明");

        Updater.State failed = state(r, idle.plan, same, false, 1000, "", "", "下载的文件校验不通过", none);
        face = UpdateFaces.update(failed, "知弦", "0.33.2");
        eq(Status.ERROR, face.tone, "失败是错误色");
        check(face.body.contains("校验") && face.primaryAction == UpdateFaces.UPDATE && "重试".equals(face.primary), "失败可重试");

        List<Updater.Export> files = new ArrayList<>();
        files.add(new Updater.Export(Updater.Export.MODULE, "SatoriQQ-module-0.34.0.zip", "content://x/1", "0.34.0"));
        Updater.State manual = state(r, idle.plan, same, false, 1000, "", "", "没有拿到 root 授权，没法直接安装。", files);
        face = UpdateFaces.update(manual, "知弦", "0.33.2");
        check(face.body.contains("下载") && face.body.contains("SatoriQQ-module-0.34.0.zip") && face.body.contains("root 管理器"), "手动安装说明");
        eq(UpdateFaces.OPEN_MODULE, face.primaryAction, "手动：打开模块");
        eq(UpdateFaces.UPDATE, face.secondaryAction, "手动：可以重试");

        files.add(new Updater.Export(Updater.Export.APP, "SatoriQQ-0.34.0.apk", "content://x/2", "0.34.0"));
        face = UpdateFaces.update(state(r, idle.plan, same, false, 1000, "", "", "", files), "知弦", "0.33.2");
        eq(UpdateFaces.OPEN_MODULE, face.primaryAction, "两个包：先装模块");
        eq(UpdateFaces.OPEN_APP, face.secondaryAction, "两个包：再装应用");

        ModuleState staged = module("present=1\nstaged=1\nversion=0.34.0\ncode=154\nstaged_version=0.34.0\nstaged_code=154\n");
        Updater.State pending = state(r, Plan.of(r, 154, staged, "0.33.2"), staged, false, 1000, "", "", "", none);
        check(UpdateFaces.update(pending, "知弦", "0.34.0") == null, "已暂存：没有更新卡");
        face = UpdateFaces.reboot(pending, "知弦", "0.33.2");
        check(face != null && face.primaryAction == UpdateFaces.REBOOT && face.body.contains("0.34.0") && face.body.contains("0.33.2"), "重启卡");

        Updater.State latest = state(r, Plan.of(r, 154, staged, "0.34.0"), module("present=1\nversion=0.34.0\ncode=154\n"), false, 1000, "", "", "", none);
        check(UpdateFaces.row(latest, "0.34.0", 1000 + 5 * 60000).contains("已是最新"), "最新");
        check(UpdateFaces.row(latest, "0.34.0", 1000 + 5 * 60000).contains("5 分钟前"), "多久前检查");
        Updater.State err = state(r, idle.plan, same, false, 1000, "连不上 GitHub", "", "", none);
        check(UpdateFaces.row(err, "0.33.2", 2000).contains("检查失败：连不上 GitHub"), "检查失败的行说明");

        Updater.State missing = state(r, Plan.of(r, 154, module(""), ""), module(""), false, 1000, "", "", "", none);
        face = UpdateFaces.update(missing, "知弦", "0.34.0");
        check(face.title.contains("没有安装") && "安装模块".equals(face.primary), "没装模块：引导安装");
    }

    private static Updater.State state(Release r, Plan plan, ModuleState module, boolean checking, long checkedAt,
                                       String checkError, String step, String failure, List<Updater.Export> exports) {
        return new Updater.State(r, plan, module, checking, checkedAt, checkError, step, failure, exports);
    }

    private static void eq(Object expect, Object got, String what) {
        if (!expect.equals(got)) throw new AssertionError(what + ": expected <" + expect + "> but was <" + got + ">");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
