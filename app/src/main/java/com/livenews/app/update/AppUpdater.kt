package com.livenews.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app updating itself: looks for a newer version, and when the user says yes, downloads it
 * and hands it to Android. Android installs it only if it is signed with the same key as the app
 * already on the phone, so a file from anywhere else cannot replace it.
 */
class AppUpdater(
    private val source: UpdateSource,
    private val installer: Installer,
    private val packageName: String,
    private val currentVersionCode: Int,
    private val downloadDir: File,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var lastQuietCheckAt = 0L

    /** The check made when the app is opened: at most every few hours, and silent unless there is an update. */
    suspend fun checkIfDue() {
        val time = now()
        if (lastQuietCheckAt != 0L && time - lastQuietCheckAt < QUIET_CHECK_EVERY_MS) return
        if (lastQuietCheckAt == 0L && _state.value is UpdateState.Idle) {
            // First check since the app started: whatever an earlier run downloaded has been
            // installed or abandoned by now.
            withContext(Dispatchers.IO) { downloadDir.deleteRecursively() }
        }
        lastQuietCheckAt = time
        check(quiet = true)
    }

    /**
     * Looks for a newer version. [quiet] is for the automatic check: it says nothing when the app
     * is up to date or the check could not be made.
     */
    suspend fun check(quiet: Boolean) {
        val before = _state.value
        if (before is UpdateState.Checking || before is UpdateState.Downloading || before is UpdateState.Installing) return
        // An offer already on screen stays there rather than flickering.
        if (quiet && (before is UpdateState.Available || before is UpdateState.NeedsPermission)) return
        if (!quiet) _state.value = UpdateState.Checking
        try {
            val info = source.latest()
            _state.value = when {
                info.versionCode > currentVersionCode -> UpdateState.Available(info)
                quiet -> UpdateState.Idle
                else -> UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            if (!quiet) _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = if (quiet) before else UpdateState.Failed("Couldn't check for updates. Check your connection and try again.", null)
        }
    }

    /** Downloads and installs the update on offer. */
    fun startUpdate() {
        val info = when (val current = _state.value) {
            is UpdateState.Available -> current.info
            is UpdateState.NeedsPermission -> current.info
            is UpdateState.Failed -> current.info ?: return
            else -> return
        }
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(info)
            installer.openPermissionSettings()
            return
        }
        _state.value = UpdateState.Downloading(info, 0f)
        scope.launch {
            try {
                val apk = File(downloadDir, "LiveNews-update.apk")
                source.download(info, apk) { progress ->
                    _state.update { if (it is UpdateState.Downloading) UpdateState.Downloading(info, progress) else it }
                }
                val facts = installer.inspect(apk)
                if (facts == null || facts.packageName != packageName || facts.versionCode <= currentVersionCode) {
                    apk.delete()
                    _state.value = UpdateState.Failed("The download was not a newer Live News, so it was not installed.", info)
                    return@launch
                }
                _state.value = UpdateState.Installing(info)
                installer.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() && it.length < 140 } ?: "The update could not be downloaded."
                _state.value = UpdateState.Failed(reason, info)
            }
        }
    }

    /** The app is back on screen: carry on if the user has just allowed installs. */
    fun onAppVisible() {
        if (_state.value is UpdateState.NeedsPermission && installer.canInstall()) startUpdate()
    }

    fun onInstalled() {
        _state.value = UpdateState.Idle
    }

    /** The user said no to Android's question: keep the offer. */
    fun onInstallCancelled() {
        _state.update { if (it is UpdateState.Installing) UpdateState.Available(it.info) else it }
    }

    fun onInstallFailed(reason: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        val detail = reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."
        _state.value = UpdateState.Failed("Android could not install the update$detail", info)
    }

    /** "Later", or closing a finished message. The next automatic check offers the update again. */
    fun dismiss() {
        _state.update {
            if (it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing) it else UpdateState.Idle
        }
    }

    private companion object {
        const val QUIET_CHECK_EVERY_MS = 6L * 60 * 60 * 1000
    }
}
