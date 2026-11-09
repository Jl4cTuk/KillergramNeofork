package aether.killergram.neo.xposed;

/** A hook that replaces the method: its result is returned instead of calling it. */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public XC_MethodReplacement() {
        super();
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            param.setResult(replaceHookedMethod(param));
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    public static final XC_MethodReplacement DO_NOTHING = returnConstant(null);

    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return result;
            }
        };
    }
}
