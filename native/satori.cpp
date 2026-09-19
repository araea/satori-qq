// 知弦的 Zygisk 原生模块。
//
// 这个 .so 由 Zygisk Next 直接加载进 QQ 的每个进程（main / :MSF ...）。它自带 ART hook 引擎
// （LSPlant，静态链接）与内联钩子后端（Dobby，静态链接），不需要任何外部 .so：NEEDED 只有
// liblog/libz/libdl/libm/libc。
//
// 为什么要自带引擎：QQ 的人脸验证会把「进程里有 LSPosed 模块」判成环境异常（实测零钩子的
// LSPosed 模块也失败），而只注入的 Zygisk 模块能过。所以框架不能在场，hook 能力得自己带。
//
// 引导顺序（都在 postAppSpecialize，此时进程已 fork 完、沙箱已生效）：
//   1. LSPlant::Init —— 符号解析器读 libart.so 的 .symtab（含带 .__uniq. 后缀的本地符号，
//      必须支持前缀匹配），内联钩子走 Dobby。
//   2. 关掉 hidden API 限制（VMRuntime.setHiddenApiExemptions，走 JNI 调用，不受 Java 侧限制）。
//   3. 用内嵌在自己 rodata 里的 classes.dex 建 InMemoryDexClassLoader（parent 用 boot
//      classloader）。把 dex 装在 .so 里是为了绕开权限问题：postAppSpecialize 时进程已在应用
//      沙箱里，读不了 /data/adb/modules。
//   4. 调 com.satori.qq.Boot.start(进程名)；拿到 QQ 的 classloader、装真正的钩子都在那之后。
#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

#include <cctype>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>

#include "zygisk.hpp"
#include "lsplant.hpp"
#include "dobby.h"
#include "elf_util.h"

#define TAG "SatoriZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 由 build.sh 生成的 dex_blob.S 提供（.incbin 进来的 classes.dex）。
extern "C" const uint8_t satori_dex_start[];
extern "C" const uint8_t satori_dex_end[];

namespace {

constexpr const char *kTarget = "com.tencent.mobileqq";

// ---- 诊断档位（交接文档 §4）------------------------------------------------
//
// 目的：把「人脸被判失败」拆成三层，定位到底哪一层的痕迹被判。
//   off    只注入，零动作（已知能过，做基线；不读 /proc、不写文件、只打日志）
//   inert  只初始化 hook 引擎，不装任何钩子（引擎自身的痕迹：libart 内联钩子 /
//          Dobby trampoline / 可执行内存）
//   boot   引擎 + 只钩引导锚点 Instrumentation.callApplicationOnCreate，
//          拿到宿主 classloader 就停（一个 bootclasspath 方法的 ArtMethod 改写）
//   hooks  完整功能（默认）
//
// 档位从 QQ 自己的 files 目录读（注入进程就是 QQ 的 uid，能读自己的数据目录）：
//   /data/data/com.tencent.mobileqq/files/satori-zygisk-mode
// 文件不存在时按 hooks 走，保证功能不受影响。
constexpr const char *kModeFile = "/data/data/com.tencent.mobileqq/files/satori-zygisk-mode";
constexpr const char *kReportFile = "/data/data/com.tencent.mobileqq/files/satori-selfcheck.txt";

std::string g_mode = "hooks";

std::string ReadModeFile() {
    int fd = open(kModeFile, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "hooks";
    char buf[64];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return "hooks";
    buf[n] = '\0';
    std::string s(buf);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ' || s.back() == '\t')) {
        s.pop_back();
    }
    size_t b = s.find_first_not_of(" \t");
    if (b != std::string::npos) s.erase(0, b); else s.clear();
    if (s.empty()) return "hooks";
    return s;
}

/**
 * 进程内自查：按 QQ 检测库读 /proc/self/maps 的思路数一遍。
 *
 * 必须从进程内读——从 Termux 里 grep /proc/<pid>/maps 是内核视角（能看见进程内
 * 过滤过的行），不是检测库视角。只读，不改任何状态。
 */
void SelfCheck(const char *stage) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == nullptr) {
        LOGE("selfcheck(%s): no /proc/self/maps", stage);
        return;
    }
    static const char *kTokens[] = {
            "lsposed", "zygisk", "magisk", "libriru", "me.bmax.apatch",
            "kernelsu", "apatch", "susfs", "lsplant", "dobby", "satori", "memfd:",
    };
    int total = 0, exec_cnt = 0, anon_exec = 0, rwx = 0, hit_cnt = 0;
    long exec_bytes = 0;
    std::string hits;
    char line[1024];
    while (fgets(line, sizeof(line), f) != nullptr) {
        total++;
        unsigned long start = 0, end = 0, off = 0, ino = 0;
        char perms[8] = {0}, dev[24] = {0}, path[600] = {0};
        int n = sscanf(line, "%lx-%lx %7s %lx %23s %lu %599[^\n]",
                       &start, &end, perms, &off, dev, &ino, path);
        bool has_path = n >= 7;
        bool is_exec = strlen(perms) >= 3 && perms[2] == 'x';
        bool is_rwx = strlen(perms) >= 3 && perms[0] == 'r' && perms[1] == 'w' && perms[2] == 'x';
        if (is_exec) {
            exec_cnt++;
            exec_bytes += static_cast<long>(end - start);
        }
        if (is_rwx) rwx++;
        // 匿名可执行段：没有路径名、又是 x。自研引擎最容易多出来的就是这一类。
        if (is_exec && !has_path) anon_exec++;
        if (!has_path) continue;
        std::string lower(path);
        while (!lower.empty() && (lower[0] == ' ' || lower[0] == '\t')) lower.erase(0, 1);
        for (char &c : lower) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
        for (const char *t : kTokens) {
            if (lower.find(t) != std::string::npos) {
                hit_cnt++;
                if (hits.size() < 4096) hits += "  " + lower + "\n";
                break;
            }
        }
    }
    fclose(f);

    char head[256];
    snprintf(head, sizeof(head),
             "[%s] stage=%s mode=%s pid=%d total=%d exec=%d(%.1fMB) anon_exec=%d rwx=%d hits=%d\n",
             TAG, stage, g_mode.c_str(), getpid(), total, exec_cnt,
             static_cast<double>(exec_bytes) / 1048576.0, anon_exec, rwx, hit_cnt);
    LOGI("%s", head);
    if (!hits.empty()) LOGI("selfcheck hits:\n%s", hits.c_str());

    FILE *out = fopen(kReportFile, "ae");
    if (out == nullptr) return;
    // 15 秒一条，别让它无限长；超过 256KB 就从头再来。
    if (ftell(out) > 256 * 1024) {
        fclose(out);
        out = fopen(kReportFile, "we");
        if (out == nullptr) return;
    }
    fputs(head, out);
    if (!hits.empty()) {
        fputs("hits:\n", out);
        fputs(hits.c_str(), out);
    }
    fclose(out);
}

bool g_self_check_loop_started = false;

void *SelfCheckLoop(void *) {
    for (;;) {
        sleep(15);
        SelfCheck("tick");
    }
    return nullptr;
}

/** 周期快照：人脸验证发生在注入很久之后，只拍开机那一下不够。 */
void StartSelfCheckLoop() {
    if (g_self_check_loop_started) return;
    g_self_check_loop_started = true;
    pthread_t t;
    if (pthread_create(&t, nullptr, SelfCheckLoop, nullptr) == 0) {
        pthread_detach(t);
    } else {
        LOGE("cannot start self-check thread");
    }
}

/** 让 Java 侧知道当前档位（Boot 用 boot 档在拿到 classloader 后停下）。 */
void SetJavaMode(JNIEnv *env, const std::string &mode) {
    jclass sys = env->FindClass("java/lang/System");
    if (sys == nullptr) { env->ExceptionClear(); return; }
    jmethodID set_prop = env->GetStaticMethodID(
            sys, "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (set_prop == nullptr) { env->ExceptionClear(); return; }
    jstring k = env->NewStringUTF("satori.zygisk.mode");
    jstring v = env->NewStringUTF(mode.c_str());
    env->CallStaticObjectMethod(sys, set_prop, k, v);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(v);
    env->DeleteLocalRef(k);
    env->DeleteLocalRef(sys);
}

elfx::Elf g_art;

void *ArtSymbol(std::string_view name) {
    if (!g_art.loaded() && !g_art.Load("/libart.so")) {
        LOGE("cannot map libart.so");
        return nullptr;
    }
    std::string n(name);
    if (void *p = g_art.Sym(n.c_str())) return p;
    // .symtab 里的 ART 内部函数常带 .__uniq.<hash> 后缀，按前缀退一步找。
    void *p = g_art.SymPrefix(name);
    if (p == nullptr) LOGI("ART symbol missing: %s", n.c_str());
    return p;
}

void *ArtPrefix(std::string_view prefix) {
    if (!g_art.loaded() && !g_art.Load("/libart.so")) return nullptr;
    return g_art.SymPrefix(prefix);
}

void *InlineHook(void *target, void *hooker) {
    void *backup = nullptr;
    return DobbyHook(target, hooker, &backup) == 0 ? backup : nullptr;
}

bool InlineUnhook(void *func) { return DobbyDestroy(func) == 0; }

/**
 * Xp.nativeHook：把 target 换成 hooker.dispatch(Object[])，返回备份方法。
 *
 * LSPlant 要求回调是 hooker 对象的**实例**方法（它会生成一个桩类去调这个成员方法），
 * 写成 static 会抛 IncompatibleClassChangeError。
 */
jobject NativeHook(JNIEnv *env, jclass, jobject target, jobject hooker) {
    if (target == nullptr || hooker == nullptr) return nullptr;
    jclass hooker_cls = env->GetObjectClass(hooker);
    if (hooker_cls == nullptr) return nullptr;
    jmethodID dispatch = env->GetMethodID(hooker_cls, "dispatch",
                                          "([Ljava/lang/Object;)Ljava/lang/Object;");
    if (dispatch == nullptr) {
        LOGE("hooker has no dispatch(Object[])");
        env->ExceptionClear();
        return nullptr;
    }
    jobject callback = env->ToReflectedMethod(hooker_cls, dispatch, JNI_FALSE);
    if (callback == nullptr) return nullptr;
    jobject backup = lsplant::Hook(env, target, hooker, callback);
    if (backup == nullptr && env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return backup;
}

jboolean NativeUnhook(JNIEnv *env, jclass, jobject target) {
    if (target == nullptr) return JNI_FALSE;
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

/** 关掉 hidden API 限制：我们的 Java 层要反射 QQ 的内部类。走 JNI 调用，不走 Java 反射。 */
void ExemptHiddenApis(JNIEnv *env) {
    jclass vm_runtime = env->FindClass("dalvik/system/VMRuntime");
    if (vm_runtime == nullptr) { env->ExceptionClear(); return; }
    jmethodID get_runtime = env->GetStaticMethodID(vm_runtime, "getRuntime",
                                                   "()Ldalvik/system/VMRuntime;");
    jmethodID set_exemptions = env->GetMethodID(vm_runtime, "setHiddenApiExemptions",
                                                "([Ljava/lang/String;)V");
    if (get_runtime == nullptr || set_exemptions == nullptr) { env->ExceptionClear(); return; }
    jobject runtime = env->CallStaticObjectMethod(vm_runtime, get_runtime);
    jclass string_cls = env->FindClass("java/lang/String");
    jstring all = env->NewStringUTF("L");  // 前缀 "L" 覆盖所有类
    jobjectArray arr = env->NewObjectArray(1, string_cls, all);
    env->CallVoidMethod(runtime, set_exemptions, arr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGI("setHiddenApiExemptions rejected; continuing without it");
    } else {
        LOGI("hidden api exemptions installed");
    }
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(all);
    env->DeleteLocalRef(runtime);
}

/** 用内嵌 dex 建一个 classloader 并调用 com.satori.qq.Boot.start(进程名)。 */
bool StartJava(JNIEnv *env, const char *process) {    const auto *begin = satori_dex_start;
    size_t size = static_cast<size_t>(satori_dex_end - satori_dex_start);
    if (size == 0) { LOGE("embedded dex is empty"); return false; }

    jobject buffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(begin), size);
    if (buffer == nullptr) { LOGE("NewDirectByteBuffer failed"); return false; }

    jclass loader_cls = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (loader_cls == nullptr) { LOGE("no InMemoryDexClassLoader"); return false; }
    jmethodID ctor = env->GetMethodID(loader_cls, "<init>",
                                      "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (ctor == nullptr) { LOGE("no InMemoryDexClassLoader ctor"); return false; }
    jobject loader = env->NewObject(loader_cls, ctor, buffer, nullptr);
    if (loader == nullptr) { env->ExceptionDescribe(); env->ExceptionClear(); return false; }

    jclass cl_cls = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(cl_cls, "loadClass",
                                            "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name = env->NewStringUTF("com.satori.qq.Boot");
    auto boot = static_cast<jclass>(env->CallObjectMethod(loader, load_class, name));
    if (env->ExceptionCheck() || boot == nullptr) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("com.satori.qq.Boot not found in the embedded dex");
        return false;
    }
    jmethodID start = env->GetStaticMethodID(boot, "start", "(Ljava/lang/String;)V");
    if (start == nullptr) { LOGE("Boot.start(String) not found"); return false; }

    // Xp 里的两个 native 方法由这里注册：模块的 .so 是 Zygisk Next 从模块目录直接加载的，
    // 不在应用的库搜索路径里，Java 侧没法 System.loadLibrary。
    jstring xp_name = env->NewStringUTF("com.satori.qq.xp.Xp");
    auto xp = static_cast<jclass>(env->CallObjectMethod(loader, load_class, xp_name));
    if (xp == nullptr || env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Xp class not found");
        return false;
    }
    static const JNINativeMethod kXpMethods[] = {
            {"nativeHook", "(Ljava/lang/reflect/Member;Ljava/lang/Object;)Ljava/lang/reflect/Member;",
             reinterpret_cast<void *>(&NativeHook)},
            {"nativeUnhook", "(Ljava/lang/reflect/Member;)Z",
             reinterpret_cast<void *>(&NativeUnhook)},
    };
    if (env->RegisterNatives(xp, kXpMethods, 2) != JNI_OK) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("RegisterNatives(Xp) failed");
        return false;
    }

    jstring proc = env->NewStringUTF(process);
    env->CallStaticVoidMethod(boot, start, proc);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    LOGI("Boot.start(%s) returned", process);
    return true;
}

void Bootstrap(JNIEnv *env, const char *process) {
    if (g_mode == "off") {
        // 基线：只留一行日志。不读 /proc、不写文件、不起线程——和当初「只注入零动作」
        // 能过人脸的那版探针保持一致。
        LOGI("mode=off: injected, doing nothing pid=%d name=%s", getpid(), process);
        return;
    }

    SelfCheck("pre-init");

    lsplant::InitInfo info{};
    info.inline_hooker = &InlineHook;
    info.inline_unhooker = &InlineUnhook;
    info.art_symbol_resolver = &ArtSymbol;
    info.art_symbol_prefix_resolver = &ArtPrefix;
    if (!lsplant::Init(env, info)) {
        LOGE("lsplant init failed in %s", process);
        return;
    }
    LOGI("lsplant ready in %s (mode=%s)", process, g_mode.c_str());
    SelfCheck("post-init");
    StartSelfCheckLoop();

    if (g_mode == "inert") {
        // 引擎初始化完了，一个钩子都不装，到这里为止。
        LOGI("mode=inert: engine initialized, no hooks installed in %s", process);
        return;
    }

    ExemptHiddenApis(env);
    SetJavaMode(env, g_mode);
    StartJava(env, process);
}

class SatoriModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (args == nullptr || args->nice_name == nullptr) return;
        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) return;
        process_ = name;
        env_->ReleaseStringUTFChars(args->nice_name, name);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (env_ == nullptr || process_.empty()) return;
        if (process_.compare(0, strlen(kTarget), kTarget) != 0) return;
        g_mode = ReadModeFile();
        Bootstrap(env_, process_.c_str());
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    std::string process_;
};

}  // namespace

REGISTER_ZYGISK_MODULE(SatoriModule)
