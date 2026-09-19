package com.satori.qq.xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射门面：对 {@code java.lang.reflect} 的一层薄封装，给 {@code Ref}（读 QQ 混淆类的唯一入口）
 * 与其它调用点用。
 *
 * <p>两条从历史实现里保留下来的行为，调用方依赖它们：参数匹配把装箱值当成对应的原始类型
 * （所以 {@code Integer 1} 能找到 {@code int} 参数）；被调方法抛出的异常原样再抛，不包
 * {@link InvocationTargetException}。
 */
public final class Reflect {

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private Reflect() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        if (classLoader == null) classLoader = Reflect.class.getClassLoader();
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(className);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return newInstance(clazz, typesOf(args), args);
    }

    public static Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object[] args) {
        Constructor<?> ctor = findConstructor(clazz, parameterTypes);
        if (ctor == null) {
            throw new NoSuchMethodError(clazz.getName() + ".<init>" + describe(parameterTypes));
        }
        ctor.setAccessible(true);
        try {
            return ctor.newInstance(args == null ? new Object[0] : args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (methodName == null) throw new IllegalArgumentException("methodName must not be null");
        if (obj == null) throw new NullPointerException("object must not be null");
        return invoke(resolve(obj.getClass(), methodName, typesOf(args), false), obj, methodName, args);
    }

    public static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes,
                                    Object... args) {
        if (methodName == null) throw new IllegalArgumentException("methodName must not be null");
        if (obj == null) throw new NullPointerException("object must not be null");
        return invoke(resolve(obj.getClass(), methodName, parameterTypes, false), obj, methodName, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        if (methodName == null) throw new IllegalArgumentException("methodName must not be null");
        return invoke(resolve(clazz, methodName, typesOf(args), true), null, methodName, args);
    }

    private static Object invoke(Method method, Object target, String methodName, Object[] args) {
        if (method == null) {
            throw new NoSuchMethodError(methodName);
        }
        method.setAccessible(true);
        try {
            return method.invoke(Modifier.isStatic(method.getModifiers()) ? null : target,
                    args == null ? new Object[0] : args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        }
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        String key = clazz.getName() + '#' + fieldName;
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) return cached;
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                FIELD_CACHE.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldError(key);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null) throw new NullPointerException("object must not be null");
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        if (obj == null) throw new NullPointerException("object must not be null");
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        if (obj == null) throw new NullPointerException("object must not be null");
        try {
            return findField(obj.getClass(), fieldName).getLong(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        Field field = findField(clazz, fieldName);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalStateException(fieldName + " is not static");
        }
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- matching

    /**
     * Prefers an exact match, then a match that only needs widening; instance methods win over
     * static ones with the same shape.
     */
    private static Method resolve(Class<?> clazz, String name, Class<?>[] actual, boolean staticOnly) {
        String key = clazz.getName() + '#' + name + describe(actual) + (staticOnly ? ":static" : "");
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;

        Method instanceExact = null, staticExact = null, instanceLoose = null, staticLoose = null;
        for (Class<?> c : hierarchy(clazz)) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if (staticOnly && !isStatic) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != actual.length) continue;
                if (matchesExactly(params, actual)) {
                    if (isStatic) { if (staticExact == null) staticExact = m; }
                    else if (instanceExact == null) instanceExact = m;
                } else if (matchesLoosely(params, actual)) {
                    if (isStatic) { if (staticLoose == null) staticLoose = m; }
                    else if (instanceLoose == null) instanceLoose = m;
                }
            }
        }
        Method found = instanceExact != null ? instanceExact
                : staticExact != null ? staticExact
                : instanceLoose != null ? instanceLoose : staticLoose;
        if (found != null) METHOD_CACHE.put(key, found);
        return found;
    }

    private static Constructor<?> findConstructor(Class<?> clazz, Class<?>[] actual) {
        Constructor<?> loose = null;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length != actual.length) continue;
            if (matchesExactly(params, actual)) return c;
            if (loose == null && matchesLoosely(params, actual)) loose = c;
        }
        return loose;
    }

    /** The class, its superclasses, and every interface reachable from them. */
    private static List<Class<?>> hierarchy(Class<?> clazz) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        List<Class<?>> out = new ArrayList<>();
        List<Class<?>> queue = new ArrayList<>();
        queue.add(clazz);
        while (!queue.isEmpty()) {
            Class<?> c = queue.remove(0);
            if (c == null || !seen.add(c)) continue;
            out.add(c);
            Class<?> superclass = c.getSuperclass();
            if (superclass != null) queue.add(superclass);
            for (Class<?> i : c.getInterfaces()) queue.add(i);
        }
        return out;
    }

    private static boolean matchesExactly(Class<?>[] declared, Class<?>[] actual) {
        for (int i = 0; i < declared.length; i++) {
            if (actual[i] == null) return false;
            if (box(declared[i]) != box(actual[i])) return false;
        }
        return true;
    }

    private static boolean matchesLoosely(Class<?>[] declared, Class<?>[] actual) {
        for (int i = 0; i < declared.length; i++) {
            if (actual[i] == null) {
                // A null argument fits anything but a primitive parameter.
                if (declared[i].isPrimitive()) return false;
            } else if (!box(declared[i]).isAssignableFrom(box(actual[i]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按实参的运行时类型推断形参类型。
     *
     * <p>**传进来的 {@code Class} 对象就是一个 Class 类型的实参**，不是「形参类型提示」——
     * 需要提示时用带 {@code parameterTypes} 的那个重载。这里曾经把 Class 当成提示，导致
     * {@code getRuntimeService(IKernelService.class, "")} 这类调用按
     * {@code [IKernelService, String]} 去匹配，一个方法都对不上，抛 NoSuchMethodError
     * （2026-09-19 实测：会话抓不到、ExtraSvc 全废）。
     */
    private static Class<?>[] typesOf(Object[] args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        return types;
    }

    private static Class<?> box(Class<?> type) {
        if (type == null || !type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static String describe(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        if (types != null) {
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(types[i] == null ? "null" : types[i].getSimpleName());
            }
        }
        return sb.append(')').toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
