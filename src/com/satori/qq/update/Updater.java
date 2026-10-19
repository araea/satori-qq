package com.satori.qq.update;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 更新的全部流程：检查线上版本 → 对照本机现状 → 下载、校验、安装。进程里只有一个，界面（Activity）来来去去，
 * 更新照样走完；界面订阅 {@link State} 的快照来画，不持有任何中间状态。
 *
 * <p>安装的顺序是：先模块，后应用。应用更新会替换自己、结束当前进程，所以放在最后，并在那之前把"已更新到
 * 某某版本"这句话存进偏好，新进程起来后再告诉用户。模块装好只是暂存，要重启手机才合并生效。
 *
 * <p>root 是首选路径：模块走管理器自带的安装命令，应用走 {@code pm install}。拿不到 root、找不到命令、
 * 或安装器报错时，退到另一条路——把安装包放进共享的「下载」文件夹，交给用户在 root 管理器 / 系统安装器里
 * 完成（{@link Export}）。两条路对用户来说结果一样：装好，重启。
 *
 * <p>所有工作排在同一条后台线程上，所以检查、探测、更新不会交错；状态只在那条线程上改，界面拿到的是不可变快照，
 * 并且总在主线程上收到。
 */
public final class Updater {
    public interface Listener {
        void changed(State state);
    }

    /** 放进「下载」文件夹、等用户手动安装的一个包。 */
    public static final class Export {
        public static final String MODULE = "module";
        public static final String APP = "app";

        public final String kind;
        public final String name;
        /** 可交给其他应用打开的链接；旧系统上为空串。 */
        public final String uri;
        public final String version;

        Export(String kind, String name, String uri, String version) {
            this.kind = kind;
            this.name = name;
            this.uri = uri;
            this.version = version;
        }

        public boolean module() {
            return MODULE.equals(kind);
        }
    }

    /** 界面画一次所需要的一切。 */
    public static final class State {
        public final Release release;
        public final Plan plan;
        public final ModuleState module;
        public final boolean checking;
        /** 最近一次成功检查的时间；0 = 从没有。 */
        public final long checkedAt;
        public final String checkError;
        /** 非空：正在更新，这是当前一步。 */
        public final String step;
        /** 非空：最近一次更新没有完成，这是原因。 */
        public final String failure;
        public final List<Export> exports;

        State(Release release, Plan plan, ModuleState module, boolean checking, long checkedAt, String checkError,
              String step, String failure, List<Export> exports) {
            this.release = release;
            this.plan = plan;
            this.module = module;
            this.checking = checking;
            this.checkedAt = checkedAt;
            this.checkError = checkError;
            this.step = step;
            this.failure = failure;
            this.exports = exports;
        }

        public boolean working() {
            return !step.isEmpty();
        }
    }

    private static final long FRESH_MS = 6L * 60 * 60 * 1000;
    private static final long RETRY_MS = 30L * 60 * 1000;
    /** root 被拒绝后，自动的探测这么久不再去敲门（Magisk 每次 su 都会弹授权框）；用户主动点更新不受限。 */
    private static final long DENIED_MS = 30L * 60 * 1000;
    private static final long INSTALL_MS = 180_000;
    private static final String PREFS = "update";
    private static final String STAGING = "/data/local/tmp/satori-qq-update";

    private static Updater instance;

    public static synchronized Updater get(Context context) {
        if (instance == null) instance = new Updater(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final String agent;
    private final long appCode;

    // 以下只在 io 线程上改。
    private Release release;
    private ModuleState module = ModuleState.UNKNOWN;
    private String running = "";
    private boolean checking;
    private long checkedAt;
    private long attemptedAt;
    private long rootDeniedAt;
    private boolean lastFailed;
    private String checkError = "";
    private String step = "";
    private String failure = "";
    private List<Export> exports = new ArrayList<>();
    private volatile State current;

    private Updater(Context context) {
        this.app = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long code = 0;
        String name = "";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            code = versionCode(info);
            name = info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 不会发生：查的是自己。
        }
        this.appCode = code;
        this.agent = Source.LABEL + "/" + name + " (update)";
        try {
            String saved = prefs.getString("release", "");
            if (!saved.isEmpty()) release = Release.parse(saved);
        } catch (JSONException ignored) {
            release = null;
        }
        String probe = prefs.getString("probe", "");
        if (!probe.isEmpty()) module = ModuleState.parse(probe);
        checkedAt = prefs.getLong("checked", 0);
        exports = loadExports();
        current = snapshot();
        io.execute(() -> {
            clearDir();
            rebuild();
        });
    }

    // ------------------------------------------------------------------ 订阅

    public State state() {
        return current;
    }

    public void listen(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.changed(current);
    }

    public void unlisten(Listener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ 偏好

    /** 打开应用时检查、后台每天检查一次并通知；默认开。 */
    public boolean auto() {
        return prefs.getBoolean("auto", true);
    }

    public void setAuto(boolean on) {
        prefs.edit().putBoolean("auto", on).apply();
        UpdateJob.schedule(app, on);
    }

    /** 我们刚替换了自己、正等着被重新打开。 */
    boolean relaunchPending() {
        return prefs.contains("just");
    }

    /** 更新刚完成并重启了应用时，新进程用这句话告诉用户；取一次就清掉。 */
    public String takeJust() {
        String text = prefs.getString("just", "");
        if (!text.isEmpty()) prefs.edit().remove("just").apply();
        return text;
    }

    /** Android 13 起通知要运行时授权；只在发现新版本时问一次，且只在用户开着自动检查时问。 */
    public boolean shouldAskNotifications() {
        return auto() && !prefs.getBoolean("asked", false);
    }

    public void markAsked() {
        prefs.edit().putBoolean("asked", true).apply();
    }

    /** 这个版本是否已经通知过（后台检查用，同一版本只通知一次）。 */
    boolean notified(String tag) {
        return tag.equals(prefs.getString("notified", ""));
    }

    void markNotified(String tag) {
        prefs.edit().putString("notified", tag).apply();
    }

    // ------------------------------------------------------------------ 输入

    /** 服务报上来的运行中的模块版本；服务不在线传空串。 */
    public void setRunning(String version) {
        final String value = version == null ? "" : version;
        io.execute(() -> {
            if (value.equals(running)) return;
            running = value;
            rebuild();
        });
    }

    /** 重新探测模块的磁盘状态（要 root）。root 刚被拒绝过就不再自动去问。 */
    public void refresh() {
        io.execute(() -> {
            if (rootDeniedAt > 0 && System.currentTimeMillis() - rootDeniedAt < DENIED_MS) return;
            probe();
        });
    }

    /** 检查线上版本。{@code force} 为 false 时按节奏：成功后 6 小时内、失败后 30 分钟内不再问。 */
    public void check(boolean force) {
        io.execute(() -> {
            if (!force) {
                if (!auto()) return;
                long wait = lastFailed ? RETRY_MS : FRESH_MS;
                long since = System.currentTimeMillis() - Math.max(attemptedAt, checkedAt);
                if (since < wait && release != null) return;
            }
            fetch();
        });
    }

    /** 给后台任务：检查完在 io 线程上回调。 */
    void checkThen(Runnable done) {
        io.execute(() -> {
            fetch();
            done.run();
        });
    }

    State peek() {
        return current;
    }

    // ------------------------------------------------------------------ 检查与探测

    private void fetch() {
        checking = true;
        publish();
        attemptedAt = System.currentTimeMillis();
        try {
            String source = prefs.getString("source", Source.MANIFEST);
            String body = Net.text(source, 256 * 1024, agent);
            release = Release.parse(body);
            checkedAt = System.currentTimeMillis();
            checkError = "";
            lastFailed = false;
            prefs.edit().putString("release", body).putLong("checked", checkedAt).apply();
        } catch (IOException error) {
            checkError = error.getMessage();
            lastFailed = true;
        } catch (JSONException error) {
            checkError = "发布清单的格式不对，请稍后再试";
            lastFailed = true;
        }
        checking = false;
        rebuild();
    }

    private void probe() {
        probeQuietly();
        rebuild();
    }

    private void probeQuietly() {
        Shell.Result result = Shell.run(ModuleState.probeScript(Source.MODULE_ID), 20_000);
        if (result.granted) {
            rootDeniedAt = 0;
            module = ModuleState.parse(result.out);
            prefs.edit().putString("probe", result.out).apply();
        } else {
            rootDeniedAt = System.currentTimeMillis();
            module = ModuleState.UNKNOWN;
            prefs.edit().remove("probe").apply();
        }
    }

    /** 状态变了：重算计划，丢掉已经不需要的导出文件，通知界面。 */
    private void rebuild() {
        Plan plan = Plan.of(release, appCode, module, running);
        if (!exports.isEmpty() && release != null) {
            List<Export> keep = new ArrayList<>();
            for (Export export : exports) {
                boolean needed = export.module() ? plan.module : plan.app;
                if (needed) keep.add(export);
                else Exports.remove(app, export.uri);
            }
            if (keep.size() != exports.size()) {
                exports = keep;
                saveExports();
            }
        }
        if (!plan.any() && step.isEmpty()) failure = "";
        publish();
    }

    private State snapshot() {
        Plan plan = Plan.of(release, appCode, module, running);
        return new State(release, plan, module, checking, checkedAt, checkError, step, failure,
                Collections.unmodifiableList(new ArrayList<>(exports)));
    }

    private void publish() {
        final State state = snapshot();
        current = state;
        main.post(() -> {
            for (Listener listener : listeners) listener.changed(state);
        });
    }

    private void progress(String text) {
        step = text;
        publish();
    }

    // ------------------------------------------------------------------ 更新

    /** 开始更新：模块先，应用后。已经在更新时忽略。 */
    public void update() {
        io.execute(() -> {
            if (!step.isEmpty() || release == null) return;
            failure = "";
            progress("正在检查 root 授权…");
            try {
                run();
            } catch (IOException error) {
                failure = error.getMessage();
            }
            step = "";
            rebuild();
        });
    }

    private void run() throws IOException {
        probeQuietly();
        final Release r = release;
        Plan plan = Plan.of(r, appCode, module, running);
        if (!plan.any()) return;
        final boolean root = module.known;
        File dir = new File(app.getCacheDir(), "update");
        clearDir();
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建下载目录");

        File zip = null;
        File apk = null;
        if (plan.module) {
            progress("正在下载模块 " + r.module.version + "…");
            zip = new File(dir, "module.zip");
            Net.download(r.module.url, zip, r.module.size, r.module.sha256, agent);
        }
        if (plan.app) {
            progress("正在下载应用 " + r.app.version + "…");
            apk = new File(dir, "app.apk");
            Net.download(r.app.url, apk, r.app.size, r.app.sha256, agent);
            verify(apk, r.app);
        }

        List<String> notes = new ArrayList<>();
        if (zip != null && !installModule(zip, r.module, root, notes)) {
            progress("正在把模块放进「下载」文件夹…");
            addExport(new Export(Export.MODULE, Source.moduleFile(r.module.version),
                    Exports.save(app, zip, Source.moduleFile(r.module.version), Exports.MODULE_MIME), r.module.version));
        }
        if (apk != null) {
            if (root) {
                installApp(apk, r, notes);
            } else {
                progress("正在把应用放进「下载」文件夹…");
                addExport(new Export(Export.APP, Source.appFile(r.app.version),
                        Exports.save(app, apk, Source.appFile(r.app.version), Exports.APP_MIME), r.app.version));
            }
        }
        if (!notes.isEmpty()) failure = String.join("\n", notes);
        else if (!exports.isEmpty() && !root) failure = "没有拿到 root 授权，没法直接安装。";
        clearDir();
    }

    /** @return 模块已经交给管理器暂存；false 表示这条路走不通，调用方改走"放进下载文件夹" */
    private boolean installModule(File zip, Release.Part part, boolean root, List<String> notes) {
        if (!root) return false;
        if (module.cli.isEmpty()) {
            notes.add("找不到 Magisk / KernelSU / APatch 的模块安装命令，已把模块放进「下载」文件夹。");
            return false;
        }
        progress("正在安装模块 " + part.version + "…");
        String staged = STAGING + ".zip";
        Shell.Result done = Shell.run("cp -f '" + zip.getPath() + "' '" + staged + "' && chmod 0644 '" + staged + "'\n"
                + ModuleState.installCommand(module.cli, staged) + "\nrc=$?\nrm -f '" + staged + "'\nexit $rc", INSTALL_MS);
        probeQuietly();
        boolean good = done.ok() && module.known && module.present && module.effectiveCode() >= part.code;
        if (!good) {
            String tail = done.tail(3);
            notes.add("模块安装器没有成功" + (tail.isEmpty() ? "" : "：" + tail) + "\n已把模块放进「下载」文件夹。");
        }
        return good;
    }

    /**
     * 用 root 替换自己。{@code pm install -r} 会在中途结束本进程（KernelSU 的 root shell 还和应用同属一个 cgroup，
     * 一起被收走），所以脚本先把自己挪出应用的 cgroup、再脱离进程组；装完重新打开应用不靠它，而是靠新版本进程里的
     * {@link UpdateReceiver}。本进程只负责在被结束之前盯着日志：装失败了（签名不符之类）才有机会告诉用户。
     */
    private void installApp(File apk, Release r, List<String> notes) throws IOException {
        progress("正在安装应用 " + r.app.version + "…");
        String staged = STAGING + ".apk";
        String log = STAGING + ".log";
        prefs.edit().putString("just", "已更新到 " + r.app.version).commit();
        Shell.Result started = Shell.run(String.join("\n",
                "rm -f '" + log + "'",
                "cp -f '" + apk.getPath() + "' '" + staged + "' && chmod 0644 '" + staged + "' || exit 1",
                "(setsid sh -c 'for g in /sys/fs/cgroup /acct; do echo $$ > $g/cgroup.procs 2>/dev/null && break; done;"
                        + " pm install -r \"$0\" > \"$1\" 2>&1; echo \"exit=$?\" >> \"$1\"; rm -f \"$0\";"
                        + " sleep 8; rm -f \"$1\"' '" + staged + "' '" + log + "' </dev/null >/dev/null 2>&1 &)"), 20_000);
        if (!started.ok()) {
            prefs.edit().remove("just").apply();
            throw new IOException("无法启动安装：" + (started.granted ? started.tail(2) : "root 授权被拒绝"));
        }
        // 成功时系统会在这期间结束本进程，下面的循环走不完；还在跑，说明没装上。
        for (int i = 0; i < 40; i++) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            Shell.Result seen = Shell.run("cat '" + log + "' 2>/dev/null", 10_000);
            if (seen.out.contains("exit=")) {
                prefs.edit().remove("just").apply();
                String reason = reason(seen.out);
                Shell.run("rm -f '" + log + "'", 5_000);
                notes.add("应用没有装上：" + reason);
                return;
            }
        }
        prefs.edit().remove("just").apply();
        notes.add("应用安装没有在预期时间内完成。");
    }

    private static String reason(String log) {
        if (log.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || log.contains("signatures do not match")) {
            return "安装包的签名与当前应用不同，需要先卸载旧版本再安装。";
        }
        if (log.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) return "线上的版本比当前的还旧。";
        if (log.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) return "存储空间不足。";
        for (String line : log.split("\n")) {
            String text = line.trim();
            if (text.startsWith("Failure") || text.startsWith("Error")) return text;
        }
        return "系统安装器拒绝了这个安装包。";
    }

    /** 装之前先核对：包名、版本号与发布清单一致，签名与当前应用相同——不同的话系统也会拒绝覆盖。 */
    private void verify(File apk, Release.Part part) throws IOException {
        PackageManager manager = app.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null) throw new IOException("下载的安装包无法解析");
        if (!Source.PACKAGE.equals(archive.packageName)) throw new IOException("下载的安装包不是" + Source.LABEL);
        if (versionCode(archive) != part.code) throw new IOException("安装包的版本与发布清单不一致");
        try {
            PackageInfo own = manager.getPackageInfo(app.getPackageName(), flags);
            if (!signers(own).equals(signers(archive))) {
                throw new IOException("安装包的签名与当前应用不同，没法覆盖更新。");
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("无法读取当前应用的签名");
        }
    }

    private static Set<String> signers(PackageInfo info) {
        Signature[] list;
        if (Build.VERSION.SDK_INT >= 28) {
            list = info.signingInfo == null ? new Signature[0] : info.signingInfo.getApkContentsSigners();
        } else {
            list = info.signatures == null ? new Signature[0] : info.signatures;
        }
        Set<String> certificates = new HashSet<>();
        for (Signature signature : list) certificates.add(signature.toCharsString());
        return certificates;
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void clearDir() {
        File dir = new File(app.getCacheDir(), "update");
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    // ------------------------------------------------------------------ 导出文件

    private void addExport(Export added) {
        List<Export> next = new ArrayList<>();
        for (Export export : exports) if (!export.kind.equals(added.kind)) next.add(export);
        next.add(added);
        exports = next;
        saveExports();
    }

    private void saveExports() {
        JSONArray array = new JSONArray();
        try {
            for (Export export : exports) {
                array.put(new JSONObject().put("kind", export.kind).put("name", export.name)
                        .put("uri", export.uri).put("version", export.version));
            }
        } catch (JSONException ignored) {
            // 都是字符串，不会失败。
        }
        prefs.edit().putString("exports", array.toString()).apply();
    }

    private List<Export> loadExports() {
        List<Export> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("exports", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                list.add(new Export(item.getString("kind"), item.getString("name"), item.optString("uri"),
                        item.optString("version")));
            }
        } catch (JSONException ignored) {
            list.clear();
        }
        return list;
    }

    // ------------------------------------------------------------------ 动作

    /** 用其他应用打开导出的包：模块交给 root 管理器，应用交给系统安装器。 */
    public Intent viewIntent(Export export) {
        if (export.uri.isEmpty()) return null;
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(export.uri), export.module() ? Exports.MODULE_MIME : Exports.APP_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return export.module() ? Intent.createChooser(intent, "用 root 管理器安装模块") : intent;
    }

    /** 系统安装器要先允许本应用安装应用；还没允许时返回跳转到那个设置页的意图。 */
    public Intent installPermissionIntent() {
        if (Build.VERSION.SDK_INT < 26 || app.getPackageManager().canRequestPackageInstalls()) return null;
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()));
    }

    /** 重启手机：模块更新要重启才合并生效。 */
    public void reboot() {
        io.execute(() -> Shell.run("sync; svc power reboot || reboot", 15_000));
    }
}
