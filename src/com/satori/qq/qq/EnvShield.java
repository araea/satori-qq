package com.satori.qq.qq;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.satori.qq.L;
import com.satori.qq.xp.XC_MethodHook;
import com.satori.qq.xp.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块自隐：本模块唯一与「检测面」有关的代码。
 *
 * <p>设计原则（与旧实现刻意相反）：</p>
 * <ul>
 *   <li>不改任何检测 API 的返回值，不拦任何上报，不碰认证/人脸链路；</li>
 *   <li>不写任何文件（旧实现往 QQ 的 files/ 下写了 29 个 {@code qk_*} 文件，本身就是证据）；</li>
 *   <li>不加载 native 库；</li>
 *   <li>只处理「本模块自己留给宿主的东西」——模块包名与被枚举出来的 Xposed 元数据。</li>
 * </ul>
 *
 * <p>{@link #audit()} 是给验证用的：在宿主进程内按 QQ 检测库的思路读一遍
 * {@code /proc/self/maps}/{@code mounts}/{@code cmdline}，数黑名单命中。它只读、不落盘，
 * 结果经 {@code /healthz} 暴露，用来判断「宿主在自己进程里还能不能看到痕迹」。</p>
 */
public final class EnvShield {

    /** 本模块包名。宿主枚举到它等于知道「这台机器上装了个 Xposed 模块」。 */
    private static final String SELF_PACKAGE = "com.satori.qq";

    /**
     * QQ 检测库黑名单里的字面量（libfekit 内的 strings 实测：{@code /proc/self/maps}、
     * {@code lsposed}、{@code zygisk}、{@code magisk}、{@code libriru}、{@code me.bmax.apatch}）。
     * 只用于 {@link #audit()} 自查，不参与任何拦截。
     */
    private static final String[] BLACKLIST = {
            "lsposed", "zygisk", "magisk", "libriru", "me.bmax.apatch", "kernelsu", "apatch", "susfs",
    };

    private static final String[] AUDIT_PATHS = {
            "/proc/self/maps", "/proc/self/smaps", "/proc/self/mounts", "/proc/self/mountinfo",
            "/proc/self/cmdline", "/proc/self/status", "/proc/self/environ",
    };

    private static volatile int hooks;
    private static volatile String lastError = "";

    private EnvShield() {
    }

    public static int hookCount() {
        return hooks;
    }

    /** 在宿主进程里装自隐钩子。幂等。 */
    public static void install(ClassLoader cl) {
        try {
            Class<?> pm = Class.forName("android.app.ApplicationPackageManager", false, cl);
            // 只挂这五个查询口：它们是枚举「装了什么」的全部入口，旧实现也是这五个。
            hookAll(pm, "getPackageInfo");
            hookAll(pm, "getApplicationInfo");
            hookAll(pm, "getInstalledPackages");
            hookAll(pm, "getInstalledApplications");
            hookAll(pm, "getPackagesForUid");
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            L.e("EnvShield install failed", t);
        }
    }

    private static void hookAll(Class<?> pm, String name) {
        try {
            XposedBridge.hookAllMethods(pm, name, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0) return;
                    if (param.args[0] instanceof String && isSelf((String) param.args[0])) {
                        param.setThrowable(new PackageManager.NameNotFoundException((String) param.args[0]));
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.getThrowable() != null) return;
                    param.setResult(scrub(param.getResult()));
                    hooks++;
                }
            });
        } catch (Throwable t) {
            lastError = name + ": " + t;
        }
    }

    private static boolean isSelf(String pkg) {
        return pkg != null && SELF_PACKAGE.equals(pkg);
    }

    /** 从查询结果里摘掉本模块，并抹掉任何 {@code xposed*} 元数据键。 */
    private static Object scrub(Object result) {
        if (result instanceof List) {
            List<?> in = (List<?>) result;
            ArrayList<Object> out = new ArrayList<>(in.size());
            for (Object item : in) {
                if (isSelf(firstString(item))) continue;
                scrubOne(item);
                out.add(item);
            }
            return out;
        }
        if (result instanceof String[]) {
            String[] in = (String[]) result;
            ArrayList<String> out = new ArrayList<>(in.length);
            for (String s : in) if (!isSelf(s)) out.add(s);
            return out;
        }
        scrubOne(result);
        return result;
    }

    private static void scrubOne(Object item) {
        if (item == null) return;
        try {
            Object meta = null;
            if (item instanceof PackageInfo) {
                meta = ((PackageInfo) item).applicationInfo == null ? null
                        : ((PackageInfo) item).applicationInfo.metaData;
            } else if (item instanceof ApplicationInfo) {
                meta = ((ApplicationInfo) item).metaData;
            }
            if (meta instanceof android.os.Bundle) {
                android.os.Bundle b = (android.os.Bundle) meta;
                for (String key : new ArrayList<>(b.keySet())) {
                    if (key != null && key.toLowerCase().startsWith("xposed")) b.remove(key);
                }
            }
        } catch (Throwable ignored) {
            // 摘元数据失败不影响主流程
        }
    }

    private static String firstString(Object item) {
        try {
            if (item instanceof PackageInfo) return ((PackageInfo) item).packageName;
            if (item instanceof ApplicationInfo) return ((ApplicationInfo) item).packageName;
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    /**
     * 在宿主进程内按检测库的思路自查一遍：这些路径里还有没有黑名单字面量。
     * 只读、不落盘、不改任何状态。
     */
    public static JSONObject audit() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray lines = new JSONArray();
        int hits = 0;
        for (String path : AUDIT_PATHS) {
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String lower = line.toLowerCase();
                    for (String token : BLACKLIST) {
                        if (lower.contains(token)) {
                            hits++;
                            if (lines.length() < 20) {
                                lines.put(new JSONObject().put("path", path)
                                        .put("token", token).put("line", shorten(line)));
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                continue;
            }
        }
        out.put("pm", pmSelfCheck());
        out.put("hits", hits);
        out.put("hooks", hooks);
        out.put("findings", lines);
        if (!lastError.isEmpty()) out.put("last_error", lastError);
        return out;
    }

    /**
     * 自隐是否真的生效：在本进程里直接查一次自己的包名。
     * 期望 {@code visible=false} 且 {@code listed=false}。
     */
    private static JSONObject pmSelfCheck() throws Exception {
        JSONObject pm = new JSONObject();
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            PackageManager mgr = ((android.content.Context) app).getPackageManager();
            try {
                mgr.getPackageInfo(SELF_PACKAGE, 0);
                pm.put("visible", true);
            } catch (PackageManager.NameNotFoundException e) {
                pm.put("visible", false);
            } catch (Throwable t) {
                pm.put("visible_error", String.valueOf(t));
            }
            try {
                boolean listed = false;
                for (PackageInfo info : mgr.getInstalledPackages(0)) {
                    if (SELF_PACKAGE.equals(info.packageName)) { listed = true; break; }
                }
                pm.put("listed", listed);
            } catch (Throwable t) {
                pm.put("listed_error", String.valueOf(t));
            }
        } catch (Throwable t) {
            pm.put("error", String.valueOf(t));
        }
        return pm;
    }

    private static String shorten(String line) {
        String s = line.trim();
        int sp = s.lastIndexOf(' ');
        return sp > 0 ? s.substring(sp + 1) : s;
    }
}
