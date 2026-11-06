package com.livenews.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.livenews.app.LiveNewsApp
import java.io.File

/** What a downloaded APK says it is. */
data class ApkFacts(val packageName: String, val versionCode: Long)

/** The Android side of installing an update. An interface so tests can run without a phone. */
interface Installer {
    /** Whether the user has allowed this app to install apps ("Install unknown apps"). */
    fun canInstall(): Boolean

    /** Opens the Android screen where that is allowed. */
    fun openPermissionSettings()

    /** null when [apk] is not a readable APK. */
    fun inspect(apk: File): ApkFacts?

    /** Hands [apk] to Android. How it went is reported to [InstallResultReceiver]. */
    fun install(apk: File)
}

class PackageApkInstaller(private val context: Context) : Installer {

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openPermissionSettings() {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(settings)
        } catch (e: Exception) {
            // A phone without that screen: Android asks in its own way when the install starts.
        }
    }

    override fun inspect(apk: File): ApkFacts? {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return null
        return ApkFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info))
    }

    override fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: an app updating itself may skip the "update this app?" question.
            // Where Android does not agree, it simply asks as before.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("LiveNews.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val result = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // Mutable because Android adds the outcome to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, result, flags).intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (ignored: Exception) {
            }
            throw e
        }
    }
}

/** Android reports here how the install went. Not exported: only the system can reach it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as LiveNewsApp).updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the user to confirm: show the screen it handed back.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                try {
                    context.startActivity(checkNotNull(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    updater.onInstallFailed("Android could not show its install screen.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstalled()
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallCancelled()
            else -> updater.onInstallFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
