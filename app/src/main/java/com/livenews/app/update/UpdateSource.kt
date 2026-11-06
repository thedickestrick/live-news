package com.livenews.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where new versions come from. An interface so tests can run without the network. */
interface UpdateSource {
    suspend fun latest(): UpdateInfo

    /** Saves the update's APK as [into]. [onProgress] gets 0 to 1, or a negative number when the size is unknown. */
    suspend fun download(info: UpdateInfo, into: File, onProgress: (Float) -> Unit)
}

class GitHubUpdateSource(client: OkHttpClient) : UpdateSource {
    // No disk cache (an APK would push every feed out of it) and no overall time limit (a slow
    // connection may need minutes); a stalled download still ends after 30 quiet seconds.
    private val client = client.newBuilder()
        .cache(null)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    override suspend fun latest(): UpdateInfo = withContext(Dispatchers.IO) {
        client.newCall(request(UpdateConfig.VERSION_JSON_URL)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The update server answered with error ${response.code}.")
            val source = (response.body ?: throw IOException("The update server sent nothing back.")).source()
            if (source.request(MAX_MANIFEST_BYTES + 1)) throw IOException("The update information is too large.")
            UpdateInfo.parse(source.readUtf8())
        }
    }

    override suspend fun download(info: UpdateInfo, into: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        into.parentFile?.mkdirs()
        val partial = File(into.path + ".part")
        client.newCall(request(info.apkUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The download failed (error ${response.code}).")
            val body = response.body ?: throw IOException("The download was empty.")
            val total = body.contentLength()
            if (total > MAX_APK_BYTES) throw IOException("The update is larger than expected, so it was not downloaded.")
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done > MAX_APK_BYTES) throw IOException("The update is larger than expected, so it was not downloaded.")
                        onProgress(if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else -1f)
                    }
                }
            }
        }
        if (into.exists()) into.delete()
        if (!partial.renameTo(into)) throw IOException("The update could not be saved.")
    }

    private companion object {
        const val USER_AGENT = "LiveNews (Android app updater)"
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
