package aether.killergram.neo.core

import aether.killergram.neo.log
import aether.killergram.neo.xposed.XposedBridge
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A tweak that can be switched without restarting the app. Its hooks are installed the
 * first time it is on and stay installed; while it is off they pass every call through.
 *
 * [keys] are the preferences it depends on (its switch and its parameters); [enabledBy]
 * decides from them whether it is on.
 */
class Feature(
    val name: String,
    val keys: Set<String>,
    private val enabledBy: (SharedPreferences) -> Boolean,
    internal val install: () -> Unit,
) : XposedBridge.Gate {
    @Volatile
    override var isOn: Boolean = false
        private set

    @Volatile
    internal var installed = false

    internal val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    internal fun read(prefs: SharedPreferences): Boolean = enabledBy(prefs).also { isOn = it }

    constructor(key: String, install: () -> Unit) :
        this(key, setOf(key), { it.getBoolean(key, false) }, install)
}

/**
 * Keeps [Feature]s in step with the module settings. The remote preferences the module app
 * writes are live in the hooked process, so a change is seen right away: a feature that is
 * turned on for the first time gets its hooks installed then (on a background thread,
 * DexKit lookups take a while), and one turned off stops acting at its next call.
 *
 * Hooks that change state once (a field, a title on screen) register [onChange] to apply
 * or undo it when their feature is switched or its parameters change.
 */
object Features {
    private val features = CopyOnWriteArrayList<Feature>()
    private var prefs: SharedPreferences? = null
    private val installer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KG Neo installer").apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Kept here: preference listeners are held weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        refresh(key)
    }

    /** Installs the features that are on and starts following the settings. */
    fun start(prefs: SharedPreferences, list: List<Feature>, afterInstall: () -> Unit) {
        this.prefs = prefs
        features.addAll(list)
        list.forEach { feature ->
            if (feature.read(prefs)) install(feature)
        }
        afterInstall()
        runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }
            .onFailure { log("Settings changes can't be followed: ${it.message}", "ERROR") }
    }

    /**
     * Runs [block] on the main thread whenever the feature whose hooks are being installed
     * is switched or its parameters change. Check `isOn` of [current] inside it.
     */
    fun onChange(block: () -> Unit) {
        val feature = current ?: return
        feature.changeListeners.add(block)
    }

    /** The feature whose hooks are being installed or run on this thread. */
    val current: Feature? get() = XposedBridge.gate as? Feature

    private fun install(feature: Feature) {
        log("Installing ${feature.name}", "DEBUG")
        runCatching { XposedBridge.withGate(feature) { feature.install() } }
            .onFailure { log("Failed to install ${feature.name}: ${it.message}", "ERROR") }
        feature.installed = true
    }

    private fun refresh(key: String?) {
        val prefs = prefs ?: return
        features.filter { key == null || key in it.keys }.forEach { feature ->
            val wasOn = feature.isOn
            val on = feature.read(prefs)
            log("${feature.name}: ${if (on) "on" else "off"}${if (on == wasOn) " (parameters)" else ""}", "DEBUG")
            if (on && !feature.installed) {
                installer.execute {
                    if (feature.installed) return@execute
                    install(feature)
                    TgDeobf.release()
                    notifyChanged(feature)
                }
            } else {
                notifyChanged(feature)
            }
        }
    }

    private fun notifyChanged(feature: Feature) {
        if (feature.changeListeners.isEmpty()) return
        mainHandler.post {
            feature.changeListeners.forEach { block ->
                runCatching { XposedBridge.withGate(feature) { block() } }
                    .onFailure { log("Failed to apply ${feature.name}: ${it.message}", "ERROR") }
            }
        }
    }
}
