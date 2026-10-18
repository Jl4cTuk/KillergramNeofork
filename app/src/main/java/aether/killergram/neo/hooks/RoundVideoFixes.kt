package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Range
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.WeakHashMap
import kotlin.math.abs

private const val STABLE_FPS = 30
private const val VIDEO_START_TIMEOUT_MS = 3000L
private const val RECENT_FRAMES = 120

/** A camera video encoder class: where it keeps its AudioRecord and its long fields. */
private class EncoderState(val audioField: Field, val longFields: List<Field>) {
    // Stamps (µs) of the latest frames handed to the encoder, as the encoder reads them.
    val recentFramesUs = ArrayDeque<Long>()
}

private class AudioState(val encoder: Any, val encoderState: EncoderState) {
    var framesRead = 0L
    var lastReadFrames = 0L
}

private val encoders = WeakHashMap<Any, EncoderState>()
private val audioStates = WeakHashMap<AudioRecord, AudioState>()

// The encoder whose frame handler is running on this thread.
private val frameEncoder = ThreadLocal<Any?>()

// The Camera2Session whose capture request is being rebuilt on this thread.
private val updatingCamera2Session = ThreadLocal<Any?>()

// Set while the Camera1 round camera applies its parameters.
private val configuringCamera1Round = ThreadLocal.withInitial { false }

/**
 * Fixes Telegram bugs that put video notes (and CameraView videos) out of sync and make
 * their frame rate wander. Measured on a Pixel against a flash + beep reference: stock
 * sound lags the picture by ~190 ms; with these fixes it stays within about ±10 ms.
 *
 * 1. Audio source: the encoders record from MediaRecorder.AudioSource.DEFAULT, whose
 *    AudioRecord timestamps leave out the capture latency on some devices (Pixel: ~190
 *    ms), so the sound lags by exactly that. CAMCORDER, the source meant for video,
 *    reports the latency.
 * 2. Audio stamps: each buffer is stamped with AudioRecord.getTimestamp().nanoTime, which
 *    belongs to the newest captured frame, not to the buffer just read. The timestamp is
 *    moved to the first frame of that buffer.
 * 3. Start-up: audio is handed over in batches of ten reads (~210 ms). When batches pile
 *    up before the first video frame is encoded, the encoder anchors the audio timeline on
 *    the newest batch but still encodes the older ones, shifting the sound by a random
 *    multiple of ~210 ms. The microphone is drained until the first frame is encoded.
 * 4. Clocks: audio uses CLOCK_MONOTONIC, camera frames the sensor clock, CLOCK_BOOTTIME on
 *    most Camera2 devices. Once the phone has slept for more than 10 s since boot, the
 *    encoder treats the two as unrelated and skips the alignment. Frame stamps are moved
 *    to CLOCK_MONOTONIC.
 * 5. Frame rate: Camera2Session asks for an AE target range of [30, 60], which most
 *    cameras do not offer, and Camera1 keeps a variable preview range. Both are pinned to
 *    a fixed 30 fps when the camera supports it.
 */
fun Hooks.fixRoundVideoRecording() {
    log("Fixing round video A/V sync and frame rate...")

    val handlers = TgDeobf.cameraFrameHandlers(lpparam)
    if (handlers.isNotEmpty()) {
        hookEncoders(handlers)
        hookFrameTimestamps(handlers)
        hookAudio()
    }
    hookCamera2FrameRate()
    hookCamera1FrameRate()
}

private fun hookEncoders(handlers: List<Method>) {
    handlers.map { it.declaringClass }.distinct().forEach { encoderClass ->
        val audioField = encoderClass.declaredFields
            .firstOrNull { it.type == AudioRecord::class.java }
            ?.apply { isAccessible = true }
            ?: run {
                log("${encoderClass.name} has no AudioRecord — its audio is left as is", "DEBUG")
                return@forEach
            }
        val longFields = encoderClass.declaredFields
            .filter { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
        XposedBridge.hookAllConstructors(encoderClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                synchronized(encoders) {
                    encoders[param.thisObject] = EncoderState(audioField, longFields)
                }
            }
        })
    }
}

private fun hookFrameTimestamps(handlers: List<Method>) {
    handlers.forEach { onFrame ->
        XposedBridge.hookMethod(onFrame, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                frameEncoder.set(param.thisObject)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                frameEncoder.set(null)
            }
        })
    }

    XposedHelpers.findAndHookMethod(SurfaceTexture::class.java, "getTimestamp", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val encoder = frameEncoder.get() ?: return
            var timestamp = param.result as? Long ?: return
            if (timestamp == 0L) return

            val boottime = SystemClock.elapsedRealtimeNanos()
            val monotonic = System.nanoTime()
            val offset = boottime - monotonic
            // Convert only once the clocks have drifted apart (the phone has slept).
            if (offset >= 1_000_000_000L && abs(timestamp - boottime) < abs(timestamp - monotonic)) {
                timestamp -= offset
                param.result = timestamp
            }

            val state = synchronized(encoders) { encoders[encoder] } ?: return
            synchronized(state.recentFramesUs) {
                state.recentFramesUs.addLast(timestamp / 1000)
                while (state.recentFramesUs.size > RECENT_FRAMES) state.recentFramesUs.removeFirst()
            }
        }
    })
}

/** The encoder has encoded its first frame: one of its fields holds that frame's µs stamp. */
private fun videoStarted(audio: AudioState): Boolean {
    val frames = synchronized(audio.encoderState.recentFramesUs) {
        audio.encoderState.recentFramesUs.toHashSet()
    }
    if (frames.isEmpty()) return false
    return audio.encoderState.longFields.any { field ->
        val value = runCatching { field.getLong(audio.encoder) }.getOrDefault(-1L)
        value > 0 && value in frames
    }
}

private fun audioStateOf(record: AudioRecord): AudioState? {
    synchronized(audioStates) { audioStates[record]?.let { return it } }
    val owner = synchronized(encoders) {
        encoders.entries.firstOrNull { (encoder, state) -> state.audioField.get(encoder) === record }
    } ?: return null
    return synchronized(audioStates) {
        audioStates.getOrPut(record) { AudioState(owner.key, owner.value) }
    }
}

private fun hookAudio() {
    // The encoders build their AudioRecord with source DEFAULT; switch it to CAMCORDER.
    XposedBridge.hookAllConstructors(AudioRecord::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.size != 5 || param.args[0] != MediaRecorder.AudioSource.DEFAULT) return
            val encoderClasses = synchronized(encoders) {
                encoders.keys.mapTo(HashSet()) { it.javaClass.name }
            }
            if (Throwable().stackTrace.none { it.className in encoderClasses }) return
            param.args[0] = MediaRecorder.AudioSource.CAMCORDER
        }
    })

    XposedHelpers.findAndHookMethod(
        AudioRecord::class.java,
        "read",
        ByteBuffer::class.java,
        Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
            // Before the first real read, drain the microphone until the encoder has
            // encoded its first video frame.
            override fun beforeHookedMethod(param: MethodHookParam) {
                val record = param.thisObject as AudioRecord
                val state = audioStateOf(record) ?: return
                if (state.framesRead > 0) return
                val size = param.args[1] as Int
                val scratch = ByteBuffer.allocateDirect(size)
                val bytesPerFrame = 2L * record.channelCount.coerceAtLeast(1)
                val deadline = SystemClock.elapsedRealtime() + VIDEO_START_TIMEOUT_MS
                while (!videoStarted(state) && SystemClock.elapsedRealtime() < deadline) {
                    scratch.clear()
                    val dropped = XposedBridge.invokeOriginalMethod(
                        param.method, record, arrayOf(scratch, size)
                    ) as? Int ?: break
                    if (dropped <= 0) break
                    state.framesRead += dropped / bytesPerFrame
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val record = param.thisObject as AudioRecord
                val bytes = param.result as? Int ?: return
                if (bytes <= 0) return
                val state = synchronized(audioStates) { audioStates[record] } ?: return
                val frames = bytes / (2L * record.channelCount.coerceAtLeast(1))
                state.framesRead += frames
                state.lastReadFrames = frames
            }
        }
    )

    XposedHelpers.findAndHookMethod(
        AudioRecord::class.java,
        "getTimestamp",
        AudioTimestamp::class.java,
        Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
            // Stamp the buffer just read with the capture time of its first frame.
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result != AudioRecord.SUCCESS) return
                val record = param.thisObject as AudioRecord
                val timestamp = param.args[0] as? AudioTimestamp ?: return
                val state = synchronized(audioStates) { audioStates[record] } ?: return
                val bufferStart = state.framesRead - state.lastReadFrames
                val queued = timestamp.framePosition - bufferStart
                if (queued <= 0) return
                timestamp.nanoTime -= queued * 1_000_000_000L / record.sampleRate
            }
        }
    )
}

private fun Hooks.hookCamera2FrameRate() {
    val sessionClass = loadClass("org.telegram.messenger.camera.Camera2Session") ?: return

    XposedBridge.hookAllMethods(sessionClass, "updateCaptureRequest", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            updatingCamera2Session.set(param.thisObject)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            updatingCamera2Session.set(null)
        }
    })

    XposedHelpers.findAndHookMethod(
        CaptureRequest.Builder::class.java,
        "set",
        CaptureRequest.Key::class.java,
        Any::class.java,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val session = updatingCamera2Session.get() ?: return
                if (param.args[0] != CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) return
                stableCamera2Range(session)?.let { param.args[1] = it }
            }
        }
    )
}

/** A fixed range (min == max) of at most [STABLE_FPS], preferring exactly [STABLE_FPS]. */
private fun stableCamera2Range(session: Any): Range<Int>? {
    val characteristics = runCatching {
        XposedHelpers.getObjectField(session, "cameraCharacteristics") as? CameraCharacteristics
    }.getOrNull() ?: return null
    val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?: return null
    return ranges
        .filter { it.lower == it.upper && it.upper <= STABLE_FPS }
        .maxByOrNull { it.upper }
}

private fun Hooks.hookCamera1FrameRate() {
    val sessionClass = loadClass("org.telegram.messenger.camera.CameraSession") ?: return

    XposedBridge.hookAllMethods(sessionClass, "configureRoundCamera", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            configuringCamera1Round.set(true)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            configuringCamera1Round.set(false)
        }
    })

    @Suppress("DEPRECATION")
    XposedHelpers.findAndHookMethod(
        Camera::class.java,
        "setParameters",
        Camera.Parameters::class.java,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (configuringCamera1Round.get() != true) return
                val parameters = param.args[0] as? Camera.Parameters ?: return
                runCatching {
                    val range = parameters.supportedPreviewFpsRange
                        ?.filter { it[0] == it[1] && it[1] <= STABLE_FPS * 1000 }
                        ?.maxByOrNull { it[1] }
                        ?: return
                    parameters.setPreviewFpsRange(range[0], range[1])
                }.onFailure {
                    log("Failed to pin Camera1 round fps: ${it.message}", "DEBUG")
                }
            }
        }
    )
}
