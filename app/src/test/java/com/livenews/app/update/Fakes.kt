package com.livenews.app.update

import java.io.File
import java.io.IOException

/** A release server for tests. [info] null behaves like being offline. */
class FakeUpdateSource(var info: UpdateInfo?) : UpdateSource {
    var checks = 0
    var downloads = 0
    var downloadProblem: String? = null
    val apkBytes = byteArrayOf(80, 75, 3, 4, 1, 2, 3)

    override suspend fun latest(): UpdateInfo {
        checks++
        return info ?: throw IOException("Unable to resolve host")
    }

    override suspend fun download(info: UpdateInfo, into: File, onProgress: (Float) -> Unit) {
        downloads++
        downloadProblem?.let { throw IOException(it) }
        into.parentFile?.mkdirs()
        onProgress(0.5f)
        into.writeBytes(apkBytes)
        onProgress(1f)
    }
}

/** Stands in for Android's installer. [facts] is what a downloaded file claims to be. */
class FakeInstaller(var allowed: Boolean, var facts: ApkFacts?) : Installer {
    var settingsOpened = 0
    val installed = ArrayList<ByteArray>()

    override fun canInstall(): Boolean = allowed

    override fun openPermissionSettings() {
        settingsOpened++
    }

    override fun inspect(apk: File): ApkFacts? = facts

    override fun install(apk: File) {
        installed.add(apk.readBytes())
    }
}
