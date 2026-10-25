package com.astracam.dualdevice.device

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log

object DeviceProfileManager {
    private const val TAG = "AstraCam_Profile"
    private const val PREFS_NAME = "astracam_prefs"
    private const val KEY_PROFILE_OVERRIDE = "profile_override"

    private var activeProfile: DeviceProfile? = null

    fun getProfile(context: Context): DeviceProfile {
        activeProfile?.let { return it }

        // Check developer fallback preference override
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val overrideName = prefs.getString(KEY_PROFILE_OVERRIDE, null)
        if (!overrideName.isNullOrEmpty()) {
            val profile = when (overrideName) {
                DeviceProfileType.H18_WATCH.name -> DeviceProfile.h18WatchProfile()
                DeviceProfileType.REDMI_NOTE_9S.name -> DeviceProfile.redmiNote9SProfile()
                else -> DeviceProfile.genericPhoneProfile()
            }
            activeProfile = profile
            Log.d(TAG, "Using overridden profile: ${profile.type}")
            return profile
        }

        // Detect screen shape and manufacturer
        val isRound = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH
                || context.resources.getBoolean(
            context.resources.getIdentifier("is_screen_round", "bool", context.packageName)
        )

        val model = Build.MODEL.uppercase()
        val device = Build.DEVICE.uppercase()
        val product = Build.PRODUCT.uppercase()
        val manufacturer = Build.MANUFACTURER.uppercase()

        val isRedmiNote9S = model.contains("REDMI NOTE 9S") ||
                device.contains("CURTANA") ||
                product.contains("CURTANA") ||
                (manufacturer.contains("XIAOMI") && model.contains("NOTE 9S"))

        val isH18Watch = isRound || model.contains("H18") || product.contains("H18")

        val profile = when {
            isH18Watch -> DeviceProfile.h18WatchProfile()
            isRedmiNote9S -> DeviceProfile.redmiNote9SProfile()
            else -> DeviceProfile.genericPhoneProfile()
        }

        activeProfile = profile
        Log.i(TAG, "Resolved profile: ${profile.type} (isRound=$isRound, model=$model, manufacturer=$manufacturer)")
        return profile
    }

    fun setProfileOverride(context: Context, type: DeviceProfileType?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (type == null) {
            prefs.edit().remove(KEY_PROFILE_OVERRIDE).apply()
        } else {
            prefs.edit().putString(KEY_PROFILE_OVERRIDE, type.name).apply()
        }
        activeProfile = null
    }
}