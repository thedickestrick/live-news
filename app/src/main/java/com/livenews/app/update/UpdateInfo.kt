package com.livenews.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Where the app looks for updates. Every GitHub release of the app carries two files:
 * version.json (read here) and LiveNews.apk. "releases/latest/download/<file>" is an address
 * GitHub keeps pointed at the newest release, so the app needs no account or token.
 */
object UpdateConfig {
    const val REPO_OWNER = "thedickestrick"
    const val REPO_NAME = "live-news"

    /** Only files under this address are ever downloaded and installed. */
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/"
    const val VERSION_JSON_URL = "${REPO_URL}releases/latest/download/version.json"
    const val APK_URL = "${REPO_URL}releases/latest/download/LiveNews.apk"
}

/** The newest published version, as written by release.ps1. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val notes: String = "",
    val apkUrl: String = "",
) {
    /** "1.2.0", or the bare number when a release forgot its name. */
    val label: String get() = versionName.ifBlank { versionCode.toString() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): UpdateInfo {
            val info = json.decodeFromString<UpdateInfo>(text.trimStart('﻿').trim())
            // Whatever the file says, the APK only ever comes from this app's own releases.
            return info.copy(apkUrl = info.apkUrl.takeIf { it.startsWith(UpdateConfig.REPO_URL) } ?: UpdateConfig.APK_URL)
        }
    }
}

sealed interface UpdateState {
    /** Nothing to show. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    /** [progress] runs 0 to 1, or is negative when the size is not known. */
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState

    /** Android has not yet been told this app may install updates; its settings screen is open. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState

    /** Handed to Android, which may be asking the user to confirm. */
    data class Installing(val info: UpdateInfo) : UpdateState

    /** [info] is the update to retry, or null when it was the check itself that failed. */
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}
