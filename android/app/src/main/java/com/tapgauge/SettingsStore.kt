package com.tapgauge

import android.content.Context

/** Simple SharedPreferences wrapper for app settings (spec section 3.6). No DataStore
 *  dependency needed for a handful of scalar prefs. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapgauge_settings", Context.MODE_PRIVATE)

    enum class Units { PERCENT, GALLONS, LITERS, LBS }

    var units: Units
        get() = runCatching { Units.valueOf(prefs.getString("units", null) ?: "PERCENT") }
            .getOrDefault(Units.PERCENT)
        set(v) = prefs.edit().putString("units", v.name).apply()

    /** AudioRecord sample rate; 44100 default, 48000 offered (spec section 5.1). */
    var sampleRate: Int
        get() = prefs.getInt("sample_rate", 44100)
        set(v) = prefs.edit().putInt("sample_rate", v).apply()

    /** Onset sensitivity: higher = requires a harder knock (spec section 3.6 / 5.2). */
    var tapThresholdRatio: Float
        get() = prefs.getFloat("tap_threshold", 6.0f)
        set(v) = prefs.edit().putFloat("tap_threshold", v).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()
}
