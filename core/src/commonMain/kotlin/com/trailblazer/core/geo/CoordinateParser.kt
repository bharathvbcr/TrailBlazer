package com.trailblazer.core.geo

import kotlin.math.abs

data class ParsedPlace(val position: LatLon, val label: String? = null)

sealed interface CoordinateParse {
    /** One or more positions, in order (directions links yield several). */
    data class Found(val places: List<ParsedPlace>) : CoordinateParse

    /** Recognized but deliberately not handled, e.g. a short link that would need a network lookup. */
    data class Refused(val reason: RefusalReason) : CoordinateParse

    data object NotRecognized : CoordinateParse
}

enum class RefusalReason { ShortLinkNeedsNetwork, NoCoordinatesInLink }

/**
 * Turns pasted or shared text into coordinates without any network access: decimal and DMS text,
 * `geo:` URIs, Google Maps, OpenStreetMap and Apple Maps links. Short links (maps.app.goo.gl, osm.org/go)
 * are refused rather than resolved, because resolving them would leak the query to a server; [ShortLinks] resolves one
 * only when the user asks.
 */
object CoordinateParser {
    private const val MAX_INPUT = 4096
    private val number = """[+-]?\d{1,3}(?:\.\d+)?"""
    private val pair = Regex("""^\s*($number)\s*,\s*($number)""")
    private val googleData = Regex("""!3d($number)!4d($number)""")
    private val googleAt = Regex("""@($number),($number)""")
    private val urlInText = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    fun parse(input: String): CoordinateParse {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_INPUT) return CoordinateParse.NotRecognized
        val lower = text.lowercase()
        return when {
            lower.startsWith("geo:") -> parseGeoUri(text)
            lower.startsWith("http://") || lower.startsWith("https://") -> parseUrl(text.substringBefore(' ').substringBefore('\n').trim())
            else -> parseText(text)?.let { CoordinateParse.Found(listOf(ParsedPlace(it))) } ?: parseShared(text) ?: CoordinateParse.NotRecognized
        }
    }

    /**
     * Text shared from a maps app: the place name (and often an address) on the lines before the link. The link is
     * parsed; the first line names the place when the link itself does not.
     */
    private fun parseShared(text: String): CoordinateParse? {
        val m = urlInText.find(text) ?: return null
        return withName(parseUrl(m.value.trimEnd('.', ',', ')', ';')), sharedName(text, m.range.first))
    }

    /**
     * Parses [fullUrl], the link a short link in [sharedText] turned out to point to, naming the place from the
     * shared text's first line when the link does not. Offline, like [parse].
     */
    fun parseResolved(sharedText: String, fullUrl: String): CoordinateParse {
        if (!fullUrl.startsWith("https://", true) && !fullUrl.startsWith("http://", true)) return CoordinateParse.NotRecognized
        if (fullUrl.length > MAX_INPUT) return CoordinateParse.NotRecognized
        val at = urlInText.find(sharedText.take(MAX_INPUT))?.range?.first ?: 0
        return withName(parseUrl(fullUrl), sharedName(sharedText, at))
    }

    /**
     * The place name a coordinate-less map link searches for (Google's `maps?q=Eiffel+Tower&ftid=…`, `/search/…`), so
     * the app can offer to search for it by name. Null when the link has coordinates or names nothing.
     */
    fun placeQuery(url: String): String? {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return null
        if (url.length > MAX_INPUT || parseUrl(url) is CoordinateParse.Found) return null
        val rest = url.substringAfter("://")
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val params = queryParams(rest.substringAfter('?', ""))
        val raw = params["q"] ?: params["query"] ?: params["destination"] ?: params["daddr"]
            ?: Regex("""(?:search|place)/([^/@]+)""").find(path)?.groupValues?.get(1)
        return raw?.let { decode(it.replace('+', ' ')) }?.trim()?.take(200)?.takeIf { it.length >= 2 && pair.find(it) == null }
    }

    /** The first non-blank line before the link, unless it is itself coordinates. */
    private fun sharedName(text: String, linkStart: Int): String? =
        text.substring(0, linkStart.coerceIn(0, text.length)).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.take(120)?.takeIf { pair.find(it) == null }

    private fun withName(r: CoordinateParse, name: String?): CoordinateParse =
        if (r is CoordinateParse.Found && name != null && r.places.size == 1 && r.places[0].label == null) {
            CoordinateParse.Found(listOf(r.places[0].copy(label = name)))
        } else r

    /** Percent-decodes, keeping a literal '+' (a sign in "+12.5", not a space). */
    private fun decode(s: String): String = try {
        urlDecode(s.replace("+", "%2B"))
    } catch (_: IllegalArgumentException) {
        s
    }

    private fun latLon(a: String, b: String): LatLon? {
        val lat = a.toDoubleOrNull() ?: return null
        val lon = b.toDoubleOrNull() ?: return null
        if (abs(lon) > 180.0) return null
        return LatLon.of(lat, lon)
    }

    private fun pairIn(s: String): LatLon? = pair.find(decode(s))?.let { latLon(it.groupValues[1], it.groupValues[2]) }

    private fun parseGeoUri(text: String): CoordinateParse {
        val body = text.substring(4)
        val query = body.substringAfter('?', "")
        val path = body.substringBefore('?').substringBefore(';')
        val q = queryParams(query)["q"]
        if (q != null) {
            val decoded = decode(q)
            val m = pair.find(decoded)
            if (m != null) {
                val p = latLon(m.groupValues[1], m.groupValues[2])
                if (p != null) {
                    val label = decoded.substringAfter('(', "").substringBeforeLast(')', "").trim().ifEmpty { null }
                    return CoordinateParse.Found(listOf(ParsedPlace(p, label)))
                }
            }
        }
        val p = pairIn(path) ?: return CoordinateParse.NotRecognized
        if (p.lat == 0.0 && p.lon == 0.0 && q != null) return CoordinateParse.Refused(RefusalReason.NoCoordinatesInLink)
        return CoordinateParse.Found(listOf(ParsedPlace(p)))
    }

    private fun queryParams(query: String): Map<String, String> =
        query.substringBefore('#').split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            if (k.isEmpty()) null else k.lowercase() to it.substringAfter('=')
        }.toMap()

    private fun parseUrl(url: String): CoordinateParse {
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase().removePrefix("www.")
        val rest = afterScheme.removePrefix(afterScheme.substringBefore('/').substringBefore('?').substringBefore('#'))
        val path = rest.substringBefore('?').substringBefore('#')
        val query = rest.substringAfter('?', "")
        val fragment = rest.substringAfter('#', "")
        val params = queryParams(query)

        if (ShortLinks.isShort(url)) {
            return CoordinateParse.Refused(RefusalReason.ShortLinkNeedsNetwork)
        }

        val found: List<ParsedPlace> = when {
            host.contains("google.") || host == "maps.google.com" -> parseGoogle(path, params)
            host.contains("openstreetmap.org") || host == "osm.org" -> parseOsm(path, params, fragment)
            host == "maps.apple.com" -> listOfNotNull(
                (params["ll"] ?: params["daddr"] ?: params["sll"])?.let { pairIn(it) }?.let {
                    ParsedPlace(it, params["q"]?.let(::decode)?.takeIf { q -> pair.find(q) == null })
                },
            )
            else -> listOfNotNull((params["q"] ?: params["ll"] ?: params["query"])?.let { pairIn(it) }?.let { ParsedPlace(it) })
        }
        return if (found.isEmpty()) CoordinateParse.Refused(RefusalReason.NoCoordinatesInLink) else CoordinateParse.Found(found)
    }

    private fun parseGoogle(path: String, params: Map<String, String>): List<ParsedPlace> {
        // In a place name '+' is a space ("Eiffel+Tower"); only coordinates keep '+' as a sign.
        val placeName = Regex("""/place/([^/@]+)""").find(path)?.groupValues?.get(1)?.let { decode(it.replace('+', ' ')) }?.trim()
            ?.takeIf { pair.find(it) == null }
        // Place links: the !3d!4d data block is the pin; '@' is only the map viewport.
        googleData.find(path)?.let { m ->
            latLon(m.groupValues[1], m.groupValues[2])?.let { return listOf(ParsedPlace(it, placeName)) }
        }
        if (params["api"] == "1" && path.contains("/dir")) {
            val stops = buildList {
                params["origin"]?.let { pairIn(it) }?.let { add(ParsedPlace(it)) }
                params["waypoints"]?.let { decode(it) }?.split('|')?.forEach { w -> pairIn(w)?.let { add(ParsedPlace(it)) } }
                params["destination"]?.let { pairIn(it) }?.let { add(ParsedPlace(it)) }
            }
            if (stops.isNotEmpty()) return stops
        }
        if (path.contains("/dir/")) {
            val stops = path.substringAfter("/dir/").split('/')
                .filter { !it.startsWith("@") && !it.startsWith("data=") }
                .mapNotNull { pairIn(it) }
                .map { ParsedPlace(it) }
            if (stops.isNotEmpty()) return stops
        }
        for (key in listOf("q", "query", "ll", "destination", "daddr", "center")) {
            params[key]?.let { pairIn(it) }?.let { return listOf(ParsedPlace(it, placeName)) }
        }
        googleAt.find(path)?.let { m ->
            latLon(m.groupValues[1], m.groupValues[2])?.let { return listOf(ParsedPlace(it, placeName)) }
        }
        return emptyList()
    }

    private fun parseOsm(path: String, params: Map<String, String>, fragment: String): List<ParsedPlace> {
        val mlat = params["mlat"]
        val mlon = params["mlon"]
        if (mlat != null && mlon != null) latLon(mlat, mlon)?.let { return listOf(ParsedPlace(it)) }
        if (path.startsWith("/directions")) {
            params["route"]?.let(::decode)?.split(';')?.mapNotNull { pairIn(it) }?.map { ParsedPlace(it) }
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val map = fragment.split('&').firstOrNull { it.startsWith("map=") }?.removePrefix("map=")?.split('/')
        if (map != null && map.size == 3) latLon(map[1], map[2])?.let { return listOf(ParsedPlace(it)) }
        return emptyList()
    }

    // ---- free text: decimal, DM, DMS, with hemisphere letters or signs ----

    private sealed interface Tok {
        data class Num(val v: Double, val negative: Boolean) : Tok
        data class Hemi(val c: Char) : Tok
        data object Sep : Tok
    }

    private fun tokenize(s: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        val t = s.uppercase()
            .replace("''", "\"").replace('′', '\'').replace('’', '\'').replace('″', '"').replace('”', '"')
        var seenUnitLetter = false
        while (i < t.length) {
            val c = t[i]
            when {
                c.isWhitespace() || c == '°' || c == 'º' || c == '˚' || c == '\'' || c == '"' || c == ':' -> i++
                c == ',' || c == ';' -> { out += Tok.Sep; seenUnitLetter = false; i++ }
                // Treat a trailing 'S' directly after a number plus M/D as seconds unit marker.
                c == 'S' && seenUnitLetter && i > 0 && (t[i - 1].isDigit() || t[i - 1] == '.') -> {
                    seenUnitLetter = false
                    i++
                }
                c in "NSEW" -> { out += Tok.Hemi(c); seenUnitLetter = false; i++ }
                c == '-' || c == '+' || c.isDigit() || c == '.' -> {
                    val start = i
                    if (c == '-' || c == '+') i++
                    while (i < t.length && (t[i].isDigit() || t[i] == '.')) i++
                    val txt = t.substring(start, i)
                    val v = txt.toDoubleOrNull() ?: return null
                    out += Tok.Num(abs(v), txt.startsWith("-"))
                }
                // Letters used as unit markers, e.g. 40d26m46s.
                c == 'D' || c == 'M' -> { seenUnitLetter = true; i++ }
                else -> return null
            }
        }
        return out
    }

    private data class Axis(val nums: List<Tok.Num>, val hemi: Char?)

    private fun splitAxes(toks: List<Tok>): Pair<Axis, Axis>? {
        val sepIndex = toks.indexOf(Tok.Sep)
        val groups: List<List<Tok>> = if (sepIndex >= 0) {
            listOf(toks.subList(0, sepIndex), toks.subList(sepIndex + 1, toks.size).filter { it != Tok.Sep })
        } else {
            val hemis = toks.withIndex().filter { it.value is Tok.Hemi }.map { it.index }
            when {
                hemis.size == 2 && hemis[0] == 0 -> listOf(toks.subList(0, hemis[1]), toks.subList(hemis[1], toks.size))
                hemis.size == 2 -> listOf(toks.subList(0, hemis[0] + 1), toks.subList(hemis[0] + 1, toks.size))
                hemis.isEmpty() -> {
                    val nums = toks.filterIsInstance<Tok.Num>()
                    if (nums.size % 2 != 0 || nums.size > 6 || nums.isEmpty()) return null
                    listOf(nums.subList(0, nums.size / 2), nums.subList(nums.size / 2, nums.size))
                }
                else -> return null
            }
        }
        val axes = groups.map { g ->
            val nums = g.filterIsInstance<Tok.Num>()
            val h = g.filterIsInstance<Tok.Hemi>()
            if (nums.isEmpty() || nums.size > 3 || h.size > 1) return null
            // A hemisphere letter must lead or trail its axis ("N 40 26", "40 26 N"), never sit between numbers.
            if (h.size == 1 && g.first() !is Tok.Hemi && g.last() !is Tok.Hemi) return null
            Axis(nums, h.firstOrNull()?.c)
        }
        return axes[0] to axes[1]
    }

    private fun axisValue(a: Axis): Double? {
        val n = a.nums
        if (n.drop(1).any { it.negative }) return null
        val negative = n[0].negative || a.hemi == 'S' || a.hemi == 'W'
        if (n[0].negative && (a.hemi == 'S' || a.hemi == 'W')) return null
        return Dms.toDecimal(n[0].v, n.getOrNull(1)?.v ?: 0.0, n.getOrNull(2)?.v ?: 0.0, negative)
    }

    private fun parseText(text: String): LatLon? {
        val toks = tokenize(text) ?: return null
        val (a, b) = splitAxes(toks) ?: return null
        val va = axisValue(a) ?: return null
        val vb = axisValue(b) ?: return null
        val aIsLon = a.hemi == 'E' || a.hemi == 'W'
        val bIsLat = b.hemi == 'N' || b.hemi == 'S'
        if (aIsLon != bIsLat && (a.hemi != null && b.hemi != null)) return null
        val (lat, lon) = if (aIsLon || bIsLat) vb to va else va to vb
        if (abs(lon) > 180.0) return null
        return LatLon.of(lat, lon)
    }
}
