package com.trailblazer.core.intents

import com.trailblazer.core.geo.LatLon
import java.net.URLEncoder
import java.util.Locale

enum class TravelMode(val google: String, val osrm: String) {
    Driving("driving", "fossgis_osrm_car"),
    Walking("walking", "fossgis_osrm_foot"),
    Bicycling("bicycling", "fossgis_osrm_bike"),
}

/**
 * Map hand-off links. Nothing here calls a map API: these are plain URIs that the user's installed
 * map app (or browser) opens. Coordinates are written with a fixed locale-independent format.
 */
object MapLinks {
    /** Google Maps URLs allow at most 9 intermediate waypoints (3 in mobile browsers). */
    const val GOOGLE_MAX_WAYPOINTS = 9

    private fun c(p: LatLon) = String.format(Locale.ROOT, "%.6f,%.6f", p.lat, p.lon)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** `geo:` URI with a labelled pin; any installed map app can handle it. */
    fun geo(p: LatLon, label: String?): String {
        val q = c(p) + (label?.takeIf { it.isNotBlank() }?.let { "(" + enc(it.replace("(", "").replace(")", "")) + ")" } ?: "")
        return "geo:${c(p)}?q=$q"
    }

    fun osmView(p: LatLon, zoom: Int = 15): String =
        String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=%d/%.6f/%.6f", p.lat, p.lon, zoom, p.lat, p.lon)

    /**
     * Google Maps directions through all [points] in order. Longer routes are split into several
     * links that chain (each link's destination is the next link's origin).
     */
    fun googleDirections(points: List<LatLon>, mode: TravelMode): List<String> {
        require(points.size >= 2) { "need at least two points" }
        val chunk = GOOGLE_MAX_WAYPOINTS + 2
        val links = ArrayList<String>()
        var i = 0
        while (i < points.size - 1) {
            val part = points.subList(i, minOf(points.size, i + chunk))
            val via = part.subList(1, part.size - 1)
            val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
            sb.append("&origin=").append(c(part.first()))
            sb.append("&destination=").append(c(part.last()))
            if (via.isNotEmpty()) sb.append("&waypoints=").append(via.joinToString("%7C") { c(it) })
            sb.append("&travelmode=").append(mode.google)
            links += sb.toString()
            i += part.size - 1
        }
        return links
    }

    /** OpenStreetMap directions support exactly two points, so one link per leg. */
    fun osmDirections(points: List<LatLon>, mode: TravelMode): List<String> {
        require(points.size >= 2) { "need at least two points" }
        return points.zipWithNext { a, b ->
            "https://www.openstreetmap.org/directions?engine=${mode.osrm}&route=${c(a)}%3B${c(b)}"
        }
    }

    /** Plain-text share for messaging apps. */
    fun shareText(name: String?, p: LatLon): String = buildString {
        name?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
        append(c(p).replace(",", ", ")).append('\n')
        append(osmView(p))
    }
}
