package com.livenews.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredArticles(val updatedAt: Long = 0L, val articles: List<Article> = emptyList())

/** Keeps the source list and the headlines in two small JSON files, so the app and widget open instantly. */
class NewsStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** null = nothing saved yet (first run), or the file is unreadable. */
    fun loadSources(): List<Source>? = read(SOURCES) { json.decodeFromString<List<Source>>(it) }

    fun saveSources(sources: List<Source>) = write(SOURCES, json.encodeToString(sources))

    fun loadArticles(): StoredArticles = read(ARTICLES) { json.decodeFromString<StoredArticles>(it) } ?: StoredArticles()

    fun saveArticles(data: StoredArticles) = write(ARTICLES, json.encodeToString(data))

    private fun <T> read(name: String, decode: (String) -> T): T? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return try {
            decode(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Writes beside the file and renames over it, so a kill mid-write never leaves half a file. */
    private fun write(name: String, text: String) {
        dir.mkdirs()
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }
    }

    private companion object {
        const val SOURCES = "sources.json"
        const val ARTICLES = "articles.json"
    }
}
