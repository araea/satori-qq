package com.satori.qq.xp;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一个被钩方法对应一个 HookEntry。它是交给 LSPlant 的 hooker 对象，
 * {@code dispatch} 必须**是实例方法**（LSPlant 会生成桩类去调 hooker 对象的这个方法；
 * 写成 static 会抛 IncompatibleClassChangeError）。
 *
 * <p>一个成员可以挂多个 {@link XC_MethodHook}：LSPlant 不允许把同一个方法钩两次，所以重复 hook
 * 同一个成员时复用同一个 entry，只往里加回调。
 *
 * <p>**必须 public**：LSPlant 生成的桩类在匿名 dex 里（没有包名），只能访问 public 成员，
 * 包级私有会让每次调用都抛 IllegalAccessError —— 而那个异常发生在被钩方法里，
 * 表现是宿主进程直接崩（第一次装机就是这么把 QQ 卡在启动界面的）。
 *
 * <p>执行顺序照搬旧 Xposed 的语义：全部 before → （没有 returnEarly 才）调原方法 → 全部 after →
 * 有 throwable 就抛出去。before/after 自身抛的异常只记日志，不带崩被钩方法。
 */
public final class HookEntry {

    private static final String TAG = "Q.Kernel";

    /**
     * hook 失败路径上的日志：它自己绝不能再抛。被钩方法已经因为坏钩子踩了一次，日志再炸一次
     * 就会把异常换成日志系统的异常（JVM 单元测试里 android.util.Log 是会抛 "Stub!" 的桩，
     * 真机上也可能因为别的原因写不进去）。
     */
    static void logFailure(String what, Throwable t) {
        try {
            Log.e(TAG, what, t);
        } catch (Throwable ignored) {
            // 记不下来就算了，不能影响被钩方法。
        }
    }

    private final Member target;
    private final boolean isStatic;
    private final boolean isConstructor;
    private final List<XC_MethodHook> callbacks = new CopyOnWriteArrayList<>();

    private volatile Member backup;

    HookEntry(Member target) {
        this.target = target;
        this.isConstructor = target instanceof Constructor;
        this.isStatic = !isConstructor && Modifier.isStatic(target.getModifiers());
    }

    void add(XC_MethodHook callback) {
        callbacks.add(callback);
    }

    void remove(XC_MethodHook callback) {
        callbacks.remove(callback);
    }

    boolean isEmpty() {
        return callbacks.isEmpty();
    }

    Member target() {
        return target;
    }

    /** 只给单元测试用：跳过 native 直接装好备份方法。 */
    void setBackupForTest(Member member) {
        backup = member;
    }

    boolean install() {
        Member b = Xp.nativeHook(target, this);
        backup = b;
        return b != null;
    }

    /** LSPlant 生成的桩调这里；args[0] 是非静态方法的 this（静态方法没有占位）。 */
    public Object dispatch(Object[] args) throws Throwable {
        Object[] callArgs;
        Object thisObject;
        if (isStatic || isConstructor) {
            thisObject = null;
            callArgs = args != null ? args : new Object[0];
        } else {
            if (args == null || args.length == 0) {
                logFailure("hook callback without this: " + target, null);
                return proceedWith(null, new Object[0]);
            }
            thisObject = args[0];
            callArgs = Arrays.copyOfRange(args, 1, args.length);
        }

        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = thisObject;
        param.args = callArgs;

        for (XC_MethodHook cb : callbacks) {
            try {
                cb.beforeHookedMethod(param);
            } catch (Throwable t) {
                logFailure("before hook failed: " + target, t);
            }
        }

        if (!param.returnEarly) {
            try {
                param.result = invokeBackup(param.thisObject, param.args);
                param.throwable = null;
            } catch (Throwable t) {
                param.throwable = t;
            }
        }

        for (XC_MethodHook cb : callbacks) {
            try {
                cb.afterHookedMethod(param);
            } catch (Throwable t) {
                logFailure("after hook failed: " + target, t);
            }
        }

        if (param.throwable != null) throw param.throwable;
        return param.result;
    }

    private Object proceedWith(Object thisObject, Object[] args) throws Throwable {
        return invokeBackup(thisObject, args);
    }

    private Object invokeBackup(Object thisObject, Object[] args) throws Throwable {
        Member m = backup;
        if (m == null) throw new IllegalStateException("no backup method for " + target);
        try {
            if (m instanceof Method) {
                Method method = (Method) m;
                Object receiver = Modifier.isStatic(method.getModifiers()) ? null : thisObject;
                return method.invoke(receiver, args);
            }
            if (m instanceof Constructor) {
                // 构造器的「原实现」没法在同一个对象上重跑；旧 Xposed 下这里同样是无解的，
                // 调用点只用 after 钩抓实例，不会走到这。
                return ((Constructor<?>) m).newInstance(args);
            }
            throw new IllegalStateException("unsupported backup member: " + m);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
