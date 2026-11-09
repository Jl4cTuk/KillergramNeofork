package aether.killergram.neo.xposed;

import java.lang.reflect.Member;

/**
 * A before/after method hook, the legacy Xposed callback shape the hooks are written in.
 * XposedBridge runs it as a libxposed API 102 interceptor. Written in Java so Kotlin hooks
 * may declare the parameter as nullable or not.
 */
public abstract class XC_MethodHook {
    public static final int PRIORITY_DEFAULT = 50;

    public final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    void callBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    void callAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static final class MethodHookParam {
        public final Member method;
        public final Object thisObject;
        public final Object[] args;

        private Object result;
        private Throwable throwable;
        boolean returnEarly;

        MethodHookParam(Member method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        /** Sets the result; in beforeHookedMethod this skips the original method. */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        /** Throws [throwable] to the caller; in beforeHookedMethod this skips the original. */
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        void setOutcome(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
        }
    }
}
