package com.best.player

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** User preferences for the player, persisted in SharedPreferences. */
class PlayerSettings(context: Context) {

    private val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)

    var brightnessGesture: Boolean
        get() = prefs.getBoolean(KEY_BRIGHTNESS, true)
        set(value) = prefs.edit().putBoolean(KEY_BRIGHTNESS, value).apply()

    var volumeGesture: Boolean
        get() = prefs.getBoolean(KEY_VOLUME, true)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME, value).apply()

    var swipeSeekGesture: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_SEEK, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_SEEK, value).apply()

    var autoPip: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PIP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PIP, value).apply()

    var doubleTapSeekSeconds: Int
        get() = prefs.getInt(KEY_DOUBLE_TAP, 10)
        set(value) = prefs.edit().putInt(KEY_DOUBLE_TAP, value).apply()

    var loop: Boolean
        get() = prefs.getBoolean(KEY_LOOP, false)
        set(value) = prefs.edit().putBoolean(KEY_LOOP, value).apply()

    /** Shows the settings dialog. [onDismiss] runs after it closes so callers can re-apply. */
    fun showDialog(context: Context, onDismiss: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)
        view.findViewById<MaterialSwitch>(R.id.sw_brightness).apply {
            isChecked = brightnessGesture
            setOnCheckedChangeListener { _, checked -> brightnessGesture = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.sw_volume).apply {
            isChecked = volumeGesture
            setOnCheckedChangeListener { _, checked -> volumeGesture = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.sw_swipe_seek).apply {
            isChecked = swipeSeekGesture
            setOnCheckedChangeListener { _, checked -> swipeSeekGesture = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.sw_auto_pip).apply {
            isChecked = autoPip
            setOnCheckedChangeListener { _, checked -> autoPip = checked }
        }
        val label = view.findViewById<TextView>(R.id.tv_double_tap)
        val slider = view.findViewById<Slider>(R.id.slider_double_tap)
        val seconds = doubleTapSeekSeconds.coerceIn(1, 60)
        slider.value = seconds.toFloat()
        label.text = context.getString(R.string.pref_double_tap, seconds)
        slider.addOnChangeListener { _, value, _ ->
            doubleTapSeekSeconds = value.toInt()
            label.text = context.getString(R.string.pref_double_tap, value.toInt())
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    private companion object {
        const val KEY_BRIGHTNESS = "gesture_brightness"
        const val KEY_VOLUME = "gesture_volume"
        const val KEY_SWIPE_SEEK = "gesture_swipe_seek"
        const val KEY_AUTO_PIP = "auto_pip"
        const val KEY_DOUBLE_TAP = "double_tap_seconds"
        const val KEY_LOOP = "loop"
    }
}
