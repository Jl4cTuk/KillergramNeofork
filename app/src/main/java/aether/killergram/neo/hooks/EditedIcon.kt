package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers
import java.lang.reflect.Field
import kotlin.math.ceil
import kotlin.math.min

// Stands in for the "edited" label while Telegram measures the time string, so the
// bubble reserves the right width; the icon span is then laid over it. A real glyph
// keeps the label readable if the span cannot be applied.
private const val EDIT_ICON_PLACEHOLDER = "✎"
private const val EDIT_ICON_SCALE = 0.75f

private val measuringTime = ThreadLocal.withInitial { false }

@Volatile private var timeStringField: Field? = null
private var editedIcon: Drawable? = null

fun Hooks.replaceEditedLabelWithIcon() {
    log("Replacing edited labels with icon...")

    // ChatMessageCell is obfuscated; measureTime and the LocaleController getters the
    // "edited" label is read with are found by fingerprint (see TgDeobf.editedTime).
    val edited = TgDeobf.editedTime(lpparam) ?: return
    if (edited.editedMessage == 0) {
        log("R.string.EditedMessage not found — edited icon skipped", "ERROR")
        return
    }

    XposedBridge.hookMethod(edited.measureTime, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            measuringTime.set(true)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            measuringTime.set(false)
            runCatching {
                applyEditedIcon(param.thisObject as? View ?: return)
            }.onFailure {
                log("Failed to apply edited icon: ${it.message}", "DEBUG")
            }
        }
    })

    // measureTime concatenates the "edited" label with the time as plain strings, so
    // swap the label for the placeholder at the source.
    edited.getString.forEach { getString ->
        XposedBridge.hookMethod(getString, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (measuringTime.get() == true && param.args[0] == edited.editedMessage) {
                    param.result = EDIT_ICON_PLACEHOLDER
                }
            }
        })
    }
}

private fun applyEditedIcon(cell: View) {
    val field = timeStringField
        ?: findTimeStringField(cell)?.also { timeStringField = it }
        ?: return
    val timeString = field.get(cell) as? CharSequence ?: return
    val index = timeString.indexOf(EDIT_ICON_PLACEHOLDER)
    if (index < 0) return

    val icon = editedIcon ?: resolveEditedIcon(cell)?.also { editedIcon = it } ?: return
    val updated = SpannableStringBuilder(timeString).apply {
        setSpan(
            EditedIconSpan(icon),
            index,
            index + EDIT_ICON_PLACEHOLDER.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    field.set(cell, updated)
}

/**
 * The cell's CharSequence field that holds the freshly built time string: the only one
 * containing the placeholder right after measureTime. Ambiguous matches are not cached.
 */
private fun findTimeStringField(cell: View): Field? {
    val matches = ArrayList<Field>()
    var current: Class<*>? = cell.javaClass
    while (current != null && current != View::class.java) {
        current.declaredFields.forEach { field ->
            if (field.type != CharSequence::class.java) return@forEach
            field.isAccessible = true
            val value = field.get(cell) as? CharSequence ?: return@forEach
            if (value.contains(EDIT_ICON_PLACEHOLDER)) matches += field
        }
        current = current.superclass
    }
    return matches.singleOrNull()
}

private fun resolveEditedIcon(cell: View): Drawable? {
    val resources = cell.context.resources
    val packageName = cell.context.packageName
    val candidates = listOf(
        "group_edit_profile", // Matches the profile header pencil icon style.
        "group_edit",
        "filled_profile_edit_24", // Telegram's filled pencil icon.
        "msg_edit",
        "menu_edit_appearance"
    )
    for (name in candidates) {
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id != 0) {
            return runCatching { resources.getDrawable(id, null) }.getOrNull()
        }
    }
    return null
}

/**
 * Draws the pencil in place of the placeholder glyph. getSize() reports the glyph's own
 * width, which is what Telegram measured with Paint.measureText, so the bubble layout
 * stays consistent. The icon follows the time color of the bubble.
 */
private class EditedIconSpan(icon: Drawable) : ReplacementSpan() {

    private val drawable: Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = ceil(paint.measureText(text, start, end)).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = paint.measureText(text, start, end)
        val fontHeight = paint.descent() - paint.ascent()
        val size = min(fontHeight * EDIT_ICON_SCALE, width * 1.15f).toInt()
        val left = (x + (width - size) / 2f).toInt()
        val centerY = y + (paint.ascent() + paint.descent()) / 2f
        val iconTop = (centerY - size / 2f).toInt()

        drawable.colorFilter = PorterDuffColorFilter(paint.color, PorterDuff.Mode.SRC_IN)
        drawable.alpha = paint.alpha
        drawable.setBounds(left, iconTop, left + size, iconTop + size)
        drawable.draw(canvas)
    }
}
