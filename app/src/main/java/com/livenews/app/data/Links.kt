package com.livenews.app.data

import java.net.URI
import java.security.MessageDigest

object Links {

    private val trackingParams = setOf(
        "fbclid", "gclid", "cmpid", "ocid", "smid", "emc", "partner", "ito", "mbid", "xtor", "ncid", "taid", "mod",
    )

    private fun isTracking(name: String) = name.startsWith("utm_") || name.startsWith("at_") || name in trackingParams

    fun isWeb(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    fun toHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /**
     * The same article reached through different feeds or tracking tags gives the same result,
     * so it is listed once. Only used for comparing; the original link is what gets opened.
     */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        return try {
            val uri = URI(trimmed)
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return trimmed
            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery
                ?.split('&')
                ?.filter { part ->
                    val name = part.substringBefore('=').lowercase()
                    name.isNotEmpty() && !isTracking(name)
                }
                ?.joinToString("&")
                .orEmpty()
            buildString {
                append("https://").append(host)
                if (uri.port != -1 && uri.port != 80 && uri.port != 443) append(':').append(uri.port)
                append(path)
                if (query.isNotEmpty()) append('?').append(query)
            }
        } catch (e: Exception) {
            trimmed
        }
    }

    fun articleId(link: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(normalize(link).toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xff
            hex.append(Character.forDigit(b shr 4, 16)).append(Character.forDigit(b and 0x0f, 16))
        }
        return hex.toString()
    }

    /** An absolute http(s) address for [href] as written inside the page at [base], or null. */
    fun resolve(base: String, href: String): String? {
        val h = href.trim()
        if (h.isEmpty()) return null
        val absolute = when {
            h.startsWith("//") -> "https:$h"
            isWeb(h) -> h
            else -> try {
                URI(base).resolve(h).toString()
            } catch (e: Exception) {
                return null
            }
        }
        return absolute.takeIf { isWeb(it) }
    }

    /** Turns what a person typed ("theverge.com", "http://x.org/feed") into an https address, or null. */
    fun cleanUserUrl(input: String): String? {
        var text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        text = when {
            text.startsWith("feed://", ignoreCase = true) -> "https://" + text.substring(7)
            isWeb(text) -> toHttps(text)
            text.contains("://") -> return null
            else -> "https://$text"
        }
        return try {
            val host = URI(text).host ?: return null
            if (host.contains('.') && !host.startsWith('.') && !host.endsWith('.')) text else null
        } catch (e: Exception) {
            null
        }
    }

    fun host(url: String): String = try {
        URI(url).host?.removePrefix("www.") ?: url
    } catch (e: Exception) {
        url
    }
}
