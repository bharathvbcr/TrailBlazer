package com.trailblazer.core.geo

import java.net.URLDecoder

/**
 * Short map links (maps.app.goo.gl/…, as Google Maps' Share button makes them) carry no coordinates: only the
 * shortener's server knows where they point. [CoordinateParser] never contacts it. When the user explicitly asks,
 * [resolve] follows the redirects — asking only shortener hosts, never loading the map page the link ends at — and
 * the full link it ends at is then parsed offline like any other.
 */
object ShortLinks {
    /** A shortener never needs more than a couple of hops; a longer chain is a loop or something else. */
    const val MAX_HOPS = 5

    private val shortHosts = setOf("goo.gl", "maps.app.goo.gl", "g.co", "bit.ly", "tinyurl.com", "t.co")
    private val urlInText = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfter('@').substringBefore(':').lowercase().removePrefix("www.")

    fun isShort(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return false
        val h = host(url)
        val path = url.substringAfter("://").removePrefix(url.substringAfter("://").substringBefore('/'))
        return h in shortHosts || (h == "osm.org" && path.startsWith("/go/"))
    }

    /** The first short link in pasted or shared text ("Place name\nAddress\nhttps://maps.app.goo.gl/…"), or null. */
    fun find(text: String): String? = urlInText.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.firstOrNull(::isShort)

    sealed interface Resolution {
        /** The full link the short one points to (not yet parsed). */
        data class Resolved(val url: String) : Resolution
        data class Failed(val reason: Failure) : Resolution
    }

    enum class Failure { NotAShortLink, NoRedirect, TooManyHops, LeftHttps }

    /**
     * Follows [start] through at most [MAX_HOPS] redirects. [hop] performs one request to a short link and returns its
     * Location header (or null when the server did not redirect); it is only ever called for a short-link URL over
     * HTTPS. Consent and "google.com/url" interstitials are unwrapped offline from their `continue`/`q` parameter.
     */
    fun resolve(start: String, hop: (String) -> String?): Resolution {
        var current = https(start) ?: return Resolution.Failed(Failure.NotAShortLink)
        if (!isShort(current)) return Resolution.Failed(Failure.NotAShortLink)
        repeat(MAX_HOPS) {
            if (!isShort(current)) return Resolution.Resolved(current)
            val next = hop(current) ?: return Resolution.Failed(Failure.NoRedirect)
            val absolute = absolute(next.trim(), current)
            current = unwrap(absolute)
            if (isShort(current)) current = https(current) ?: return Resolution.Failed(Failure.LeftHttps)
        }
        return if (isShort(current)) Resolution.Failed(Failure.TooManyHops) else Resolution.Resolved(current)
    }

    /** Upgrades a short link to HTTPS (all the listed shorteners serve it); anything else is refused. */
    private fun https(url: String): String? = when {
        url.startsWith("https://", ignoreCase = true) -> url
        url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring(7)
        else -> null
    }

    private fun absolute(location: String, base: String): String = when {
        location.startsWith("https://", true) || location.startsWith("http://", true) -> location
        location.startsWith("//") -> "https:$location"
        location.startsWith("/") -> "https://" + base.substringAfter("://").substringBefore('/') + location
        else -> location
    }

    /** consent.google.com/…?continue=<link> and google.com/url?q=<link> wrap the real destination. */
    internal fun unwrap(url: String): String {
        val h = host(url)
        val key = when {
            h == "consent.google.com" -> "continue"
            h.startsWith("google.") && url.substringAfter("://").substringAfter('/', "").startsWith("url?") -> "q"
            else -> return url
        }
        val query = url.substringAfter('?', "").substringBefore('#')
        val raw = query.split('&').firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }?.substringAfter('=') ?: return url
        val inner = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: IllegalArgumentException) {
            return url
        }
        return if (inner.startsWith("https://", true) || inner.startsWith("http://", true)) inner else url
    }
}
