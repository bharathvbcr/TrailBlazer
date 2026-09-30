package com.example.trailblazer.links

import com.trailblazer.core.geo.CoordinateParse
import com.trailblazer.core.geo.CoordinateParser
import com.trailblazer.core.geo.ShortLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One request to a short link: its redirect target, or null when it did not redirect. Throws IOException. */
fun interface RedirectProbe {
    fun location(url: String): String?
}

/**
 * Asks for the Location header only: redirects are not followed by the connection, the body is never read, no
 * cookies are kept, and timeouts are bounded. HTTPS only.
 */
class UrlConnectionRedirectProbe : RedirectProbe {
    override fun location(url: String): String? {
        require(url.startsWith("https://")) { "only https is allowed" }
        val target = URL(url)
        // The resolver vetted the host with its own parser; refuse if java.net.URL would connect somewhere else.
        require(target.host.lowercase().removePrefix("www.") == ShortLinks.host(url) && ShortLinks.isShort(url)) { "not a short link" }
        val c = target.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            // GET, not HEAD: some shorteners answer HEAD with 404/405. Only the status line and headers are read.
            return if (c.responseCode in 300..399) c.getHeaderField("Location")?.take(MAX_LOCATION) else null
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val MAX_LOCATION = 8_192
    }
}

/** What a lookup the user asked for came to. */
sealed interface LinkLookupResult {
    data class Parsed(val parse: CoordinateParse, val resolvedUrl: String) : LinkLookupResult
    data class Failed(val message: String) : LinkLookupResult
}

/**
 * Resolves a short map link when, and only when, the user taps "Look up online". Only the link itself is sent, to the
 * shortener that issued it; the full link it points to is parsed offline.
 */
class LinkLookup(private val probe: RedirectProbe) {
    /** [sharedText] is what was pasted: the short link, or a maps app's share text around it. */
    suspend fun resolve(sharedText: String): LinkLookupResult = withContext(Dispatchers.IO) {
        val shortUrl = ShortLinks.find(sharedText) ?: return@withContext LinkLookupResult.Failed("There is no short link to look up.")
        try {
            when (val r = ShortLinks.resolve(shortUrl, probe::location)) {
                is ShortLinks.Resolution.Resolved -> LinkLookupResult.Parsed(CoordinateParser.parseResolved(sharedText, r.url), r.url)
                is ShortLinks.Resolution.Failed -> LinkLookupResult.Failed(
                    when (r.reason) {
                        ShortLinks.Failure.NotAShortLink -> "That is not a short link."
                        ShortLinks.Failure.NoRedirect -> "The link did not lead anywhere. It may have expired."
                        ShortLinks.Failure.TooManyHops -> "The link kept redirecting, so the lookup stopped."
                        ShortLinks.Failure.LeftHttps -> "The link redirected to an insecure address, so the lookup stopped."
                    },
                )
            }
        } catch (e: IOException) {
            LinkLookupResult.Failed("Couldn’t reach the link (${e.message ?: "network error"}). You may be offline.")
        } catch (e: IllegalArgumentException) {
            LinkLookupResult.Failed("Couldn’t read the link.")
        }
    }
}
