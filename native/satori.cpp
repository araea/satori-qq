// 知弦的 Zygisk 原生模块。
//
// 这个 .so 由 Zygisk Next 直接加载进 QQ 的每个进程（main / :MSF ...），不带任何 ART hook
// 引擎：全部能力都走 JNI 层。为什么换成这条路：自带 LSPlant 的版本功能完全正常，但人脸验证
// 仍被判失败，而「零动作只注入」的探针能过——被判的正是 hook 引擎自己留下的痕迹（libart
// 内联钩子、可执行 trampoline、生成的 hooker dex）。所以这里一条 ArtMethod 都不改写。
//
// 用到的 JNI 层手段：
//   1. 引导：.so 起来后在独立线程里轮询 android.app.ActivityThread.currentApplication()，
//      拿到 Application 的 classloader。**不需要钩 Instrumentation.callApplicationOnCreate**。
//   2. 加载自己：dex 用 .incbin 嵌在 .so 的 rodata（注入后进程已在应用沙箱里，读不了
//      /data/adb/modules），运行时 InMemoryDexClassLoader 加载。
//   3. 注册自己：模块 .so 不在应用的库搜索路径里，System.loadLibrary 找不到它，所以 native
//      侧自己 RegisterNatives 把 Xp 的入口挂上去。
//   4. 抓会话：QQ 的会话对象不用钩构造器——IKernelService.getWrapperSession() 就是公开接口
//      方法，Java 侧反射直接拿（见 QQClient）。
//   5. 抓回包：QQNT 的裸 SSO 请求只有一条回包路，就是 native 方法
//      IQQNTWrapperSession$CppProxy.native_onSendSSOReply。用 RegisterNatives 换成自己的
//      实现（纯 JNI，不改 ArtMethod）；非本模块的 requestId 再转交原实现——原函数指针从
//      ArtMethod 的 data_ 字段读出来（只读不写，见 InstallSsoHook 里的前后比对）。
//
// 没有 STL、没有第三方依赖：NEEDED 只有 liblog/libdl/libm/libc。
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>

#include "zygisk.hpp"

#define TAG "SatoriZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 由 build.sh 生成的 dex_blob.S 提供（.incbin 进来的 classes.dex）。
extern "C" const uint8_t satori_dex_start[];
extern "C" const uint8_t satori_dex_end[];

static const char *kNLogFile = "/data/data/com.tencent.mobileqq/files/satori-native.log";
static const char *kTarget = "com.tencent.mobileqq";
static const char *kXpClass = "com.satori.qq.xp.Xp";
static const char *kSsoClass = "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy";
static const char *kSsoMethod = "native_onSendSSOReply";
static const char *kSsoSignature =
        "(JJLjava/lang/String;ILjava/lang/String;Lcom/tencent/qqnt/kernel/nativeinterface/MsfRspInfo;)V";
static const char *kSsoCallbackClass = "com.satori.qq.packet.PacketSvc";
static const char *kSsoCallbackMethod = "onNativeSsoReply";
static const char *kSsoCallbackSignature = "(JLjava/lang/String;ILjava/lang/String;Ljava/lang/Object;)Z";

static JavaVM *g_vm = nullptr;
static jclass g_sso_class = nullptr;          // 全局引用
static jclass g_callback_class = nullptr;     // 全局引用
static jmethodID g_callback_method = nullptr;
static void *g_orig_sso_reply = nullptr;
static char g_process[256] = {0};
static bool g_bootstrap_started = false;
static char g_hook_info[256] = "not-attempted";
static unsigned long g_sso_calls = 0;       // 进过我们替换实现的次数
static unsigned long g_sso_consumed = 0;    // 其中被本模块消费掉的次数

/**
 * 记一行到 logcat **并**落到 QQ 私有目录。
 *
 * 只写 logcat 不够：主缓冲只有 256KiB，QQ 启动时几秒就把它刷掉了，重启后回头再看什么都没有
 * （2026-09-19 首启实测）。这个文件也不给 healthz 用，是事后翻查用的。
 */
static void NLog(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
    FILE *f = fopen(kNLogFile, "ae");
    if (f == nullptr) return;
    fprintf(f, "%s\n", buf);
    fclose(f);
}

// 替换 QQ native 方法的函数：必须在取地址之前先行声明。
static void SatoriSsoReply(JNIEnv *env, jobject thiz, jlong native_ref, jlong request_id,
                           jstring cmd, jint result_code, jstring error_msg, jobject info);

// ---- JNI 小工具 -----------------------------------------------------------------

/** 在当前线程拿 JNIEnv；native 线程要自己 attach。 */
static JNIEnv *GetEnv(bool *attached) {
    *attached = false;
    if (g_vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    jint r = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (r == JNI_OK) return env;
    if (r == JNI_EDETACHED && g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}

static void ReleaseEnv(bool attached) {
    if (attached && g_vm != nullptr) g_vm->DetachCurrentThread();
}

/** 用给定 classloader 按名字取类；取不到返回 nullptr（并清掉异常）。 */
static jclass LoadClass(JNIEnv *env, jobject loader, const char *name) {
    if (loader == nullptr) return nullptr;
    jclass loader_cls = env->FindClass("java/lang/ClassLoader");
    if (loader_cls == nullptr) { env->ExceptionClear(); return nullptr; }
    jmethodID load_class = env->GetMethodID(loader_cls, "loadClass",
                                            "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class == nullptr) { env->ExceptionClear(); return nullptr; }
    jstring n = env->NewStringUTF(name);
    auto cls = static_cast<jclass>(env->CallObjectMethod(loader, load_class, n));
    env->DeleteLocalRef(n);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    return cls;
}

/** 关掉 hidden API 限制：Java 侧要反射 QQ 的内部类。走 JNI 调用，不走 Java 反射。 */
static void ExemptHiddenApis(JNIEnv *env) {
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
    }
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(all);
    env->DeleteLocalRef(runtime);
}

/**
 * 轮询宿主的 Application。
 *
 * 这就是「不用钩子也能引导」的那一步：0.22.0 的 Instrumentation.callApplicationOnCreate
 * 钩子只是当时拿 classloader 的手段，而 ActivityThread 上本来就有公开静态方法一直返回它。
 * 三条路依次退：currentApplication() → currentActivityThread().getApplication() → 读
 * mInitialApplication 字段。
 */
static jobject CurrentApplicationOnce(JNIEnv *env, jclass at_cls, jmethodID current_app,
                                      jmethodID current_at, jmethodID get_app,
                                      jfieldID initial_app) {
    if (current_app != nullptr) {
        jobject a = env->CallStaticObjectMethod(at_cls, current_app);
        if (env->ExceptionCheck()) { env->ExceptionClear(); }
        else if (a != nullptr) return a;
    }
    jobject at = nullptr;
    if (current_at != nullptr) {
        at = env->CallStaticObjectMethod(at_cls, current_at);
        if (env->ExceptionCheck()) { env->ExceptionClear(); at = nullptr; }
    }
    if (at != nullptr) {
        if (get_app != nullptr) {
            jobject a = env->CallObjectMethod(at, get_app);
            if (env->ExceptionCheck()) { env->ExceptionClear(); }
            else if (a != nullptr) return a;
        }
        if (initial_app != nullptr) {
            jobject a = env->GetObjectField(at, initial_app);
            if (env->ExceptionCheck()) { env->ExceptionClear(); }
            else if (a != nullptr) return a;
        }
    }
    return nullptr;
}

static jobject WaitForApplication(JNIEnv *env, int timeout_ms) {
    jclass at_cls = env->FindClass("android/app/ActivityThread");
    if (at_cls == nullptr) { env->ExceptionDescribe(); env->ExceptionClear(); return nullptr; }
    jmethodID current_app = env->GetStaticMethodID(at_cls, "currentApplication",
                                                   "()Landroid/app/Application;");
    env->ExceptionClear();
    jmethodID current_at = env->GetStaticMethodID(at_cls, "currentActivityThread",
                                                  "()Landroid/app/ActivityThread;");
    env->ExceptionClear();
    jmethodID get_app = env->GetMethodID(at_cls, "getApplication",
                                         "()Landroid/app/Application;");
    env->ExceptionClear();
    jfieldID initial_app = env->GetFieldID(at_cls, "mInitialApplication",
                                           "Landroid/app/Application;");
    env->ExceptionClear();
    if (current_app == nullptr && current_at == nullptr) {
        LOGE("ActivityThread has no currentApplication/currentActivityThread");
        return nullptr;
    }
    for (int waited = 0; waited < timeout_ms; waited += 20) {
        jobject app = CurrentApplicationOnce(env, at_cls, current_app, current_at, get_app,
                                             initial_app);
        if (app != nullptr) return app;
        usleep(20 * 1000);
    }
    return nullptr;
}

static jobject HostLoaderFromApplication(JNIEnv *env, jobject app) {
    jclass context_cls = env->FindClass("android/content/Context");
    jmethodID get_loader = env->GetMethodID(context_cls, "getClassLoader",
                                            "()Ljava/lang/ClassLoader;");
    if (get_loader == nullptr) { env->ExceptionClear(); return nullptr; }
    jobject loader = env->CallObjectMethod(app, get_loader);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    return loader;
}

static jobject MakeEmbeddedLoader(JNIEnv *env, jobject parent) {
    const auto *begin = satori_dex_start;
    size_t size = static_cast<size_t>(satori_dex_end - satori_dex_start);
    if (size == 0) { LOGE("embedded dex is empty"); return nullptr; }
    jobject buffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(begin), size);
    if (buffer == nullptr) { LOGE("NewDirectByteBuffer failed"); return nullptr; }
    jclass loader_cls = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (loader_cls == nullptr) { env->ExceptionDescribe(); env->ExceptionClear(); return nullptr; }
    jmethodID ctor = env->GetMethodID(loader_cls, "<init>",
                                      "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (ctor == nullptr) { env->ExceptionDescribe(); env->ExceptionClear(); return nullptr; }
    jobject loader = env->NewObject(loader_cls, ctor, buffer, parent);
    if (loader == nullptr) { env->ExceptionDescribe(); env->ExceptionClear(); return nullptr; }
    return loader;
}

// ---- native_onSendSSOReply 的替换 -------------------------------------------------

/**
 * 在 ArtMethod 里找 data_（native 方法里存的 JNI 函数指针）。
 *
 * 不写死字段偏移，靠两条特征一起认：值必须落在一段可执行的映射里，而且不在 libart（那里是
 * 通用 JNI trampoline 的地址）也不在本模块自己的 .so 里。
 */
static bool MappingOf(const void *p, bool *exec, char *path, size_t path_size) {
    if (path_size > 0) path[0] = '\0';
    if (p == nullptr) return false;
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == nullptr) return false;
    char line[1024];
    auto target = reinterpret_cast<uintptr_t>(p);
    bool found = false;
    while (fgets(line, sizeof(line), f) != nullptr) {
        uintptr_t start = 0, end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (target < start || target >= end) continue;
        *exec = perms[2] == 'x';
        // 路径在第 6 个字段之后；找不到就留空（匿名映射）。
        const char *sp = strchr(line, ' ');
        for (int i = 0; sp != nullptr && i < 4; i++) sp = strchr(sp + 1, ' ');
        if (sp != nullptr) {
            while (*sp == ' ') sp++;
            snprintf(path, path_size, "%s", sp);
            size_t n = strlen(path);
            while (n > 0 && (path[n - 1] == '\n' || path[n - 1] == ' ')) path[--n] = '\0';
        }
        found = true;
        break;
    }
    fclose(f);
    return found;
}

static bool IsExecutableAddress(const void *p) {
    bool exec = false;
    return MappingOf(p, &exec, nullptr, 0) && exec;
}

/**
 * 原实现必须在**某个第三方 .so**里。
 *
 * 我们装钩子的时机可能早于 QQ 原生库注册这块 JNI：那时 data_ 里放的还是 ART 的解析存根
 * （落在 /system 或 boot.oat 里），把它当成原实现去调，存根会把调用派回方法自己的 JNI 入口
 * ——也就是我们，于是两边互相递归，每秒几百万次把 CPU 打满（2026-09-19 实测）。
 */
static bool LooksLikeThirdPartyJni(void *p, char *path, size_t path_size) {
    bool exec = false;
    if (!MappingOf(p, &exec, path, path_size) || !exec) return false;
    if (path[0] == '\0') return false;                       // 匿名映射：说不清是谁的，不装
    if (strstr(path, "/system/") != nullptr) return false;
    if (strstr(path, "/apex/") != nullptr) return false;
    if (strstr(path, ".oat") != nullptr) return false;
    return strstr(path, ".so") != nullptr;
}

static int FindJniEntrySlot(uintptr_t *words, int kWords, void **out, char *path, size_t path_size) {
    for (int i = 0; i < kWords; ++i) {
        auto *p = reinterpret_cast<void *>(words[i]);
        char candidate[512];
        if (!LooksLikeThirdPartyJni(p, candidate, sizeof(candidate))) continue;
        if (strstr(candidate, "libsatori") != nullptr) continue;
        *out = p;
        snprintf(path, path_size, "%s", candidate);
        return i;
    }
    return -1;
}

/**
 * 装 SSO 回包的拦截。
 *
 * RegisterNatives 不给旧函数指针，所以先自己把原实现读出来：在 ArtMethod 里认出 data_
 * （见 {@link FindJniEntrySlot}），认不出来就**不注册**——装了却转交不了原实现会把 QQ 自己
 * 的 SSO 回包全丢掉。注册完再验一次那个字是不是变成了自己的函数地址，不是就用原指针原样
 * 注册回去。
 */
static bool InstallSsoHook(JNIEnv *env, jobject loader) {
    jclass cpp = LoadClass(env, loader, kSsoClass);
    if (cpp == nullptr) {
        snprintf(g_hook_info, sizeof(g_hook_info), "failed: cannot load %s", kSsoClass);
        NLog("%s", g_hook_info);
        return false;
    }
    jclass cb = LoadClass(env, loader, kSsoCallbackClass);
    if (cb == nullptr) {
        snprintf(g_hook_info, sizeof(g_hook_info), "failed: cannot load %s", kSsoCallbackClass);
        NLog("%s", g_hook_info);
        return false;
    }
    jmethodID cb_method = env->GetStaticMethodID(cb, kSsoCallbackMethod, kSsoCallbackSignature);
    if (cb_method == nullptr) {
        env->ExceptionClear();
        snprintf(g_hook_info, sizeof(g_hook_info), "failed: no callback %s", kSsoCallbackMethod);
        NLog("%s", g_hook_info);
        return false;
    }

    jmethodID mid = env->GetMethodID(cpp, kSsoMethod, kSsoSignature);
    if (mid == nullptr) {
        env->ExceptionClear();
        snprintf(g_hook_info, sizeof(g_hook_info), "failed: no %s in QQ", kSsoMethod);
        NLog("%s", g_hook_info);
        return false;
    }

    auto *words = reinterpret_cast<uintptr_t *>(mid);
    constexpr int kWords = 8;

    void *orig = nullptr;
    char lib[512] = {0};
    int slot = FindJniEntrySlot(words, kWords, &orig, lib, sizeof(lib));
    if (slot < 0) {
        snprintf(g_hook_info, sizeof(g_hook_info),
                 "failed: no third-party jni entry in ArtMethod (natives not registered yet?)");
        NLog("%s", g_hook_info);
        return false;
    }

    JNINativeMethod method{kSsoMethod, const_cast<char *>(kSsoSignature),
                           reinterpret_cast<void *>(&SatoriSsoReply)};
    if (env->RegisterNatives(cpp, &method, 1) != JNI_OK) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        snprintf(g_hook_info, sizeof(g_hook_info), "failed: RegisterNatives rejected");
        NLog("%s", g_hook_info);
        return false;
    }
    if (words[slot] != reinterpret_cast<uintptr_t>(&SatoriSsoReply)) {
        JNINativeMethod back{kSsoMethod, const_cast<char *>(kSsoSignature), orig};
        env->RegisterNatives(cpp, &back, 1);
        env->ExceptionClear();
        snprintf(g_hook_info, sizeof(g_hook_info), "failed: slot %d did not take our fn", slot);
        NLog("%s", g_hook_info);
        return false;
    }

    g_callback_class = static_cast<jclass>(env->NewGlobalRef(cb));
    g_callback_method = cb_method;
    g_orig_sso_reply = orig;
    g_sso_class = static_cast<jclass>(env->NewGlobalRef(cpp));
    snprintf(g_hook_info, sizeof(g_hook_info), "installed slot=%d orig=%p in %s", slot, orig, lib);
    NLog("%s jni entry at slot %d: %p in %s", kSsoMethod, slot, orig, lib);
    return true;
}

/** Xp.nativeSsoHookInfo()：healthz 用，不用翻 logcat。 */
static jstring NativeSsoHookInfo(JNIEnv *env, jclass) {
    char buf[320];
    snprintf(buf, sizeof(buf), "%s calls=%lu consumed=%lu", g_hook_info,
             __atomic_load_n(&g_sso_calls, __ATOMIC_RELAXED),
             __atomic_load_n(&g_sso_consumed, __ATOMIC_RELAXED));
    return env->NewStringUTF(buf);
}

/** 把回包交给 Java 侧；返回 true 表示本模块已经消费掉，不要再喂给 QQ 原生会话。 */
static thread_local bool g_in_sso_reply = false;

static void SatoriSsoReply(JNIEnv *env, jobject thiz, jlong native_ref, jlong request_id,
                           jstring cmd, jint result_code, jstring error_msg, jobject info) {
    // 重入护栏：如果被换掉的那个入口本来就指向「派回方法自身」的存根，转交原实现会再进到这里。
    // 没有这道闸就是每秒几百万次的互相递归（实测过）。重入直接返回。
    if (g_in_sso_reply) return;
    g_in_sso_reply = true;
    __atomic_add_fetch(&g_sso_calls, 1, __ATOMIC_RELAXED);
    if (g_callback_method != nullptr) {
        jboolean consumed = env->CallStaticBooleanMethod(
                g_callback_class, g_callback_method, request_id, cmd, result_code, error_msg, info);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        } else if (consumed == JNI_TRUE) {
            __atomic_add_fetch(&g_sso_consumed, 1, __ATOMIC_RELAXED);
            g_in_sso_reply = false;
            return;
        }
    }
    if (g_orig_sso_reply != nullptr) {
        using OrigFn = void (*)(JNIEnv *, jobject, jlong, jlong, jstring, jint, jstring, jobject);
        reinterpret_cast<OrigFn>(g_orig_sso_reply)(
                env, thiz, native_ref, request_id, cmd, result_code, error_msg, info);
    }
    g_in_sso_reply = false;
}

/** Xp.nativeInstallSsoHook(ClassLoader)：Java 侧在拿到宿主 classloader 后调用一次。 */
static jboolean NativeInstallSsoHook(JNIEnv *env, jclass, jobject loader) {
    if (g_orig_sso_reply != nullptr) return JNI_TRUE;  // 幂等
    return InstallSsoHook(env, loader) ? JNI_TRUE : JNI_FALSE;
}

// ---- 引导 ------------------------------------------------------------------------

static bool StartJava(JNIEnv *env, jobject loader, jobject host, const char *process) {
    jclass boot = LoadClass(env, loader, "com.satori.qq.Boot");
    if (boot == nullptr) { LOGE("com.satori.qq.Boot not found in the embedded dex"); return false; }
    jmethodID start = env->GetStaticMethodID(
            boot, "start", "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (start == nullptr) { LOGE("Boot.start(String, ClassLoader) not found"); return false; }

    jclass xp = LoadClass(env, loader, kXpClass);
    if (xp == nullptr) { LOGE("Xp class not found"); return false; }
    static const JNINativeMethod kXpMethods[] = {
            {"nativeInstallSsoHook", "(Ljava/lang/ClassLoader;)Z",
             reinterpret_cast<void *>(&NativeInstallSsoHook)},
            {"nativeSsoHookInfo", "()Ljava/lang/String;",
             reinterpret_cast<void *>(&NativeSsoHookInfo)},
    };
    if (env->RegisterNatives(xp, kXpMethods, 2) != JNI_OK) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("RegisterNatives(Xp) failed");
        return false;
    }

    jstring proc = env->NewStringUTF(process);
    env->CallStaticVoidMethod(boot, start, proc, host);
    env->DeleteLocalRef(proc);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    LOGI("Boot.start(%s) returned", process);
    return true;
}

static void *BootstrapThread(void *) {
    bool attached = false;
    JNIEnv *env = GetEnv(&attached);
    if (env == nullptr) { LOGE("cannot attach bootstrap thread"); return nullptr; }

    // 先放开 hidden API：下面要问的 ActivityThread 那几个入口都是 hidden 的。
    ExemptHiddenApis(env);

    jobject app = WaitForApplication(env, 120000);
    if (app == nullptr) { NLog("Application never appeared"); ReleaseEnv(attached); return nullptr; }
    NLog("application ready in %s", g_process);

    jobject host = HostLoaderFromApplication(env, app);
    if (host == nullptr) { NLog("no host classloader"); ReleaseEnv(attached); return nullptr; }
    NLog("host classloader captured");

    // 内嵌 dex 的父加载器就用宿主的：这样模块自己的 Java 代码可以直接按名字引用 QQ 的类，
    // 也让 native 侧 loadClass 一次就能同时找到两边的类。
    jobject loader = MakeEmbeddedLoader(env, host);
    if (loader == nullptr) { NLog("cannot create embedded dex loader"); ReleaseEnv(attached); return nullptr; }

    NLog("StartJava(%s)", g_process);
    StartJava(env, loader, host, g_process);
    ReleaseEnv(attached);
    return nullptr;
}

class SatoriModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        (void) api;
        env_ = env;
        env->GetJavaVM(&g_vm);
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (args == nullptr || args->nice_name == nullptr || env_ == nullptr) return;
        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) return;
        if (strncmp(name, kTarget, strlen(kTarget)) == 0) {
            strncpy(g_process, name, sizeof(g_process) - 1);
        }
        env_->ReleaseStringUTFChars(args->nice_name, name);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (g_process[0] == '\0' || g_bootstrap_started) return;
        g_bootstrap_started = true;
        pthread_t t;
        if (pthread_create(&t, nullptr, BootstrapThread, nullptr) == 0) {
            pthread_detach(t);
            LOGI("bootstrap thread started for %s", g_process);
        } else {
            LOGE("cannot start bootstrap thread");
        }
    }

private:
    JNIEnv *env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(SatoriModule)
