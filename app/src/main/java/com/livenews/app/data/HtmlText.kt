package com.livenews.app.data

/** Turns the HTML fragments feeds put in titles and descriptions into plain text. */
object HtmlText {
    private val hidden = Regex("""(?is)<(script|style)\b[^>]*>.*?</\1\s*>""")
    private val comments = Regex("""(?s)<!--.*?-->""")
    private val breaks = Regex("""(?i)<(br|/p|/div|/li|/h[1-6]|/tr)\b[^>]*>""")
    private val tags = Regex("""</?[a-zA-Z!][^>]*>""")
    private val entities = Regex("""&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,10});""")
    private val whitespace = Regex("""\s+""")
    private val imgTag = Regex("""(?is)<img\b[^>]*>""")
    private val srcAttr = Regex("""(?is)\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val tinyAttr = Regex("""(?i)\b(?:width|height)\s*=\s*["']?[01](?:px)?["']?(?=[\s>/]|$)""")
    private val trackers = listOf("feedburner.com/~", "doubleclick.net", "/pixel", "pixel.", "feeds.feedblitz", "stats.wp.com", "/b.gif", "/1x1")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„",
        "hellip" to "…", "bull" to "•", "middot" to "·", "laquo" to "«", "raquo" to "»",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "times" to "×",
        "pound" to "£", "euro" to "€", "yen" to "¥", "cent" to "¢", "sect" to "§",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "plusmn" to "±", "minus" to "−",
        "prime" to "′", "Prime" to "″", "shy" to "", "zwnj" to "", "zwj" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "agrave" to "à", "egrave" to "è", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù",
        "acirc" to "â", "ecirc" to "ê", "icirc" to "î", "ocirc" to "ô", "ucirc" to "û",
        "auml" to "ä", "euml" to "ë", "iuml" to "ï", "ouml" to "ö", "uuml" to "ü",
        "atilde" to "ã", "ntilde" to "ñ", "otilde" to "õ", "ccedil" to "ç", "szlig" to "ß",
        "aring" to "å", "oslash" to "ø", "aelig" to "æ",
        "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú",
        "Agrave" to "À", "Egrave" to "È", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü",
        "Ntilde" to "Ñ", "Ccedil" to "Ç", "Aring" to "Å", "Oslash" to "Ø", "AElig" to "Æ",
    )

    /** What browsers show for the numeric references 128-159, which sites still emit from Windows-1252 text. */
    private val windows1252 = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F" +
        "\u0090‘’“”•–—˜™š›œ\u009DžŸ"

    fun toPlain(html: String?, maxLength: Int = Int.MAX_VALUE): String {
        if (html.isNullOrEmpty()) return ""
        var text = stripTags(html)
        if (text.indexOf('&') >= 0) {
            text = decodeEntities(text)
            // Some feeds escape their HTML twice, which only shows up as tags after decoding.
            if (text.indexOf('<') >= 0 && tags.containsMatchIn(text)) text = stripTags(text)
        }
        text = whitespace.replace(text.replace(' ', ' '), " ").trim()
        return truncate(text, maxLength)
    }

    /** The address of the first real picture in an HTML fragment (skips tracking pixels). */
    fun firstImage(html: String?): String? {
        if (html.isNullOrEmpty() || !html.contains("<img", ignoreCase = true)) return null
        for (match in imgTag.findAll(html)) {
            val tag = match.value
            if (tinyAttr.containsMatchIn(tag)) continue
            val src = srcAttr.find(tag) ?: continue
            val raw = (src.groups[1] ?: src.groups[2] ?: src.groups[3])?.value ?: continue
            val url = decodeEntities(raw.trim())
            if (url.isEmpty() || url.startsWith("data:", ignoreCase = true)) continue
            if (trackers.any { url.contains(it, ignoreCase = true) }) continue
            return url
        }
        return null
    }

    private fun stripTags(html: String): String {
        if (html.indexOf('<') < 0) return html
        var text = hidden.replace(html, " ")
        text = comments.replace(text, " ")
        text = breaks.replace(text, " ")
        return tags.replace(text, "")
    }

    private fun decodeEntities(text: String): String = entities.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16))
            body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
            else -> named[body] ?: named[body.lowercase()]
        } ?: match.value
    }

    private fun codePoint(code: Int?): String? = when {
        code == null || code <= 0 -> null
        code in 0x80..0x9F -> windows1252[code - 0x80].toString()
        code in 0xD800..0xDFFF || !Character.isValidCodePoint(code) -> null
        else -> String(Character.toChars(code))
    }

    private fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val cut = text.lastIndexOf(' ', maxLength)
        val end = if (cut > maxLength * 6 / 10) cut else maxLength
        return text.substring(0, end).trimEnd(' ', ',', ';', ':', '.', '-', '–', '—') + "…"
    }
}
