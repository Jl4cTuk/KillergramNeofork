package aether.killergram.neo.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Legacy XposedBridge entry points on top of libxposed API 102. A hook is an interceptor
 * that runs the callback's before part, proceeds unless it set a result, then runs the
 * after part. Exceptions from callbacks are logged and ignored, as in legacy Xposed.
 */
object XposedBridge {
    private const val TAG = "KG Neo"

    /** The module's framework interface, set when the module is loaded. */
    @Volatile
    lateinit var framework: XposedInterface

    val isReady: Boolean get() = this::framework.isInitialized

    @JvmStatic
    fun hookMethod(member: Member, callback: XC_MethodHook): XposedInterface.HookHandle {
        val executable = member as Executable
        return framework.hook(executable)
            .setPriority(callback.priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> intercept(chain, callback) }
    }

    private fun intercept(chain: XposedInterface.Chain, callback: XC_MethodHook): Any? {
        val param = XC_MethodHook.MethodHookParam(
            chain.executable,
            chain.thisObject,
            chain.args.toTypedArray(),
        )
        try {
            callback.callBefore(param)
        } catch (t: Throwable) {
            log("Error in beforeHookedMethod of ${chain.executable}", t)
            param.returnEarly = false
            param.setOutcome(null, null)
        }

        if (!param.returnEarly) {
            try {
                param.setOutcome(chain.proceed(param.args), null)
            } catch (t: Throwable) {
                param.setOutcome(null, t)
            }
        }

        val result = param.result
        val throwable = param.throwable
        try {
            callback.callAfter(param)
        } catch (t: Throwable) {
            log("Error in afterHookedMethod of ${chain.executable}", t)
            param.setOutcome(result, throwable)
        }

        param.throwable?.let { throw it }
        return param.result
    }

    @JvmStatic
    fun hookAllMethods(cls: Class<*>, name: String, callback: XC_MethodHook): Set<XposedInterface.HookHandle> =
        cls.declaredMethods.filter { it.name == name }.map { hookMethod(it, callback) }.toSet()

    @JvmStatic
    fun hookAllConstructors(cls: Class<*>, callback: XC_MethodHook): Set<XposedInterface.HookHandle> =
        cls.declaredConstructors.map { hookMethod(it, callback) }.toSet()

    /** Calls [member] without the hooks on it. */
    @JvmStatic
    fun invokeOriginalMethod(member: Member, thisObject: Any?, args: Array<Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (member) {
            is Method -> framework.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(thisObject, *arguments)
            is Constructor<*> -> framework.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .newInstance(*arguments)
            else -> throw IllegalArgumentException("Not a method or constructor: $member")
        }
    }

    @JvmStatic
    @JvmOverloads
    fun log(message: String, priority: Int = Log.INFO) {
        framework.log(priority, TAG, message)
    }

    @JvmStatic
    fun log(message: String, throwable: Throwable) {
        framework.log(Log.ERROR, TAG, message, throwable)
    }
}
