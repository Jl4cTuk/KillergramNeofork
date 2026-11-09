package aether.killergram.neo.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The reflection helpers of legacy XposedHelpers the hooks use. Lookups search the class
 * hierarchy and are cached; failures throw like the originals (NoSuchFieldError,
 * NoSuchMethodError, ClassNotFoundError). Written in Java so Kotlin callers see platform
 * types, as with the original.
 */
public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();
    private static final Map<String, Method> methodCache = new ConcurrentHashMap<>();
    private static final Map<Object, HashMap<String, Object>> additionalFields = new WeakHashMap<>();

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    public static Field findField(Class<?> cls, String name) {
        String key = cls.getName() + '#' + name;
        Field cached = fieldCache.get(key);
        if (cached != null) return cached;
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                fieldCache.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldError(key);
    }

    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        //noinspection unchecked
        throw (T) t;
    }

    private static Object get(Field field, Object obj) {
        try {
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    private static void set(Field field, Object obj, Object value) {
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getObjectField(Object obj, String name) {
        return get(findField(obj.getClass(), name), obj);
    }

    public static void setObjectField(Object obj, String name, Object value) {
        set(findField(obj.getClass(), name), obj, value);
    }

    public static int getIntField(Object obj, String name) {
        return (Integer) get(findField(obj.getClass(), name), obj);
    }

    public static void setIntField(Object obj, String name, int value) {
        set(findField(obj.getClass(), name), obj, value);
    }

    public static long getLongField(Object obj, String name) {
        return (Long) get(findField(obj.getClass(), name), obj);
    }

    public static boolean getBooleanField(Object obj, String name) {
        return (Boolean) get(findField(obj.getClass(), name), obj);
    }

    public static void setBooleanField(Object obj, String name, boolean value) {
        set(findField(obj.getClass(), name), obj, value);
    }

    public static void setFloatField(Object obj, String name, float value) {
        set(findField(obj.getClass(), name), obj, value);
    }

    public static Object getStaticObjectField(Class<?> cls, String name) {
        return get(findField(cls, name), null);
    }

    public static void setStaticObjectField(Class<?> cls, String name, Object value) {
        set(findField(cls, name), null, value);
    }

    public static int getStaticIntField(Class<?> cls, String name) {
        return (Integer) get(findField(cls, name), null);
    }

    public static void setStaticIntField(Class<?> cls, String name, int value) {
        set(findField(cls, name), null, value);
    }

    public static boolean getStaticBooleanField(Class<?> cls, String name) {
        return (Boolean) get(findField(cls, name), null);
    }

    private static final Map<Class<?>, Class<?>> BOXED = new HashMap<>();

    static {
        BOXED.put(boolean.class, Boolean.class);
        BOXED.put(byte.class, Byte.class);
        BOXED.put(char.class, Character.class);
        BOXED.put(short.class, Short.class);
        BOXED.put(int.class, Integer.class);
        BOXED.put(long.class, Long.class);
        BOXED.put(float.class, Float.class);
        BOXED.put(double.class, Double.class);
    }

    private static boolean accepts(Class<?>[] params, Object[] args) {
        if (params.length != args.length) return false;
        for (int i = 0; i < params.length; i++) {
            Object arg = args[i];
            Class<?> param = params[i];
            if (arg == null) {
                if (param.isPrimitive()) return false;
            } else {
                Class<?> boxed = BOXED.get(param);
                if (!(boxed != null ? boxed : param).isInstance(arg)) return false;
            }
        }
        return true;
    }

    /** The method [name] of [cls], its superclasses or interfaces that accepts [args]. */
    public static Method findMethodBestMatch(Class<?> cls, String name, Object... args) {
        StringBuilder key = new StringBuilder(cls.getName()).append('#').append(name).append('(');
        for (Object arg : args) key.append(arg == null ? "null" : arg.getClass().getName()).append(',');
        String cacheKey = key.append(')').toString();
        Method cached = methodCache.get(cacheKey);
        if (cached != null) return cached;
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
                    method.setAccessible(true);
                    methodCache.put(cacheKey, method);
                    return method;
                }
            }
        }
        for (Method method : cls.getMethods()) {
            if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
                method.setAccessible(true);
                methodCache.put(cacheKey, method);
                return method;
            }
        }
        throw new NoSuchMethodError(cacheKey);
    }

    private static Object invoke(Method method, Object obj, Object[] args) {
        try {
            return method.invoke(obj, args);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getTargetException());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object callMethod(Object obj, String name, Object... args) {
        return invoke(findMethodBestMatch(obj.getClass(), name, args), obj, args);
    }

    public static Object callStaticMethod(Class<?> cls, String name, Object... args) {
        return invoke(findMethodBestMatch(cls, name, args), null, args);
    }

    private static Class<?>[] parameterTypes(ClassLoader classLoader, Object[] types) {
        Class<?>[] result = new Class<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            Object type = types[i];
            if (type instanceof Class<?>) {
                result[i] = (Class<?>) type;
            } else if (type instanceof String) {
                result[i] = findClass((String) type, classLoader);
            } else {
                throw new IllegalArgumentException("Parameter type must be a Class or a String: " + type);
            }
        }
        return result;
    }

    private static XC_MethodHook callback(Object[] typesAndCallback) {
        if (typesAndCallback.length == 0 || !(typesAndCallback[typesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("No callback defined");
        }
        return (XC_MethodHook) typesAndCallback[typesAndCallback.length - 1];
    }

    /** findAndHookMethod(class, name, paramTypes…, callback). */
    public static Object findAndHookMethod(Class<?> cls, String name, Object... typesAndCallback) {
        XC_MethodHook callback = callback(typesAndCallback);
        Class<?>[] types = parameterTypes(cls.getClassLoader(),
                Arrays.copyOf(typesAndCallback, typesAndCallback.length - 1));
        try {
            Method method = cls.getDeclaredMethod(name, types);
            return XposedBridge.hookMethod(method, callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(cls.getName() + '#' + name);
        }
    }

    public static Object findAndHookMethod(String className, ClassLoader classLoader, String name, Object... typesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), name, typesAndCallback);
    }

    public static Object findAndHookConstructor(Class<?> cls, Object... typesAndCallback) {
        XC_MethodHook callback = callback(typesAndCallback);
        Class<?>[] types = parameterTypes(cls.getClassLoader(),
                Arrays.copyOf(typesAndCallback, typesAndCallback.length - 1));
        try {
            Constructor<?> constructor = cls.getDeclaredConstructor(types);
            return XposedBridge.hookMethod(constructor, callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(cls.getName() + "#<init>");
        }
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        synchronized (additionalFields) {
            HashMap<String, Object> fields = additionalFields.get(obj);
            if (fields == null) {
                fields = new HashMap<>();
                additionalFields.put(obj, fields);
            }
            return fields.put(key, value);
        }
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        synchronized (additionalFields) {
            HashMap<String, Object> fields = additionalFields.get(obj);
            return fields == null ? null : fields.get(key);
        }
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        synchronized (additionalFields) {
            HashMap<String, Object> fields = additionalFields.get(obj);
            return fields == null ? null : fields.remove(key);
        }
    }
}
