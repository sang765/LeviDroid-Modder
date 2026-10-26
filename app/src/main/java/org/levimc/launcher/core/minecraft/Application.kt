package org.levimc.launcher.core.minecraft

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.core.news.NewsNotificationHelper
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager
import org.levimc.launcher.util.PersonalizationManager

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Force-disable ForceDark globally for the launcher to prevent SurfaceSyncer stutter on BP-heavy worlds
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        context = applicationContext
        FeatureSettings.init(applicationContext)
        CrashReporter.init(this)
        val processName = Application.getProcessName()
        if (processName.endsWith(":crash")) return

        NewsNotificationHelper.initialize(this)
        LogcatOverlayManager.init(this)
        PersonalizationManager.startWallpaperAccentTracking(this)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set
    }
}
