package com.example.trailblazer.places

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.example.trailblazer.data.Waypoint
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** A place to offer in the picker: a search result, a waypoint or a stop from another trip. */
data class FoundPlace(val position: LatLon, val name: String, val detail: String? = null)

/** One lookup by name. Throws IOException when the service cannot be reached. */
fun interface PlaceSearchBackend {
    suspend fun lookup(query: String, max: Int): List<FoundPlace>
}

sealed interface SearchResult {
    data class Ok(val places: List<FoundPlace>) : SearchResult
    data object NotConsented : SearchResult
    data object NotAvailable : SearchResult
    data class Failed(val message: String) : SearchResult
    data class Refused(val message: String) : SearchResult
}

/**
 * Search by place name. Nothing is sent without consent or when the phone has no geocoder; the query is the only
 * thing sent, it is bounded, and every result is checked to be a real position before it is shown.
 */
class PlaceSearch(private val backend: PlaceSearchBackend, private val available: () -> Boolean) {
    suspend fun search(query: String, consented: Boolean): SearchResult {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < MIN_QUERY) return SearchResult.Refused("Type at least $MIN_QUERY letters.")
        if (q.length > MAX_QUERY) return SearchResult.Refused("That is too long to search for.")
        if (!consented) return SearchResult.NotConsented
        if (!available()) return SearchResult.NotAvailable
        return try {
            val found = withTimeoutOrNull(TIMEOUT_MS) { backend.lookup(q, MAX_RESULTS) }
                ?: return SearchResult.Failed("The search took too long. You may be offline.")
            SearchResult.Ok(
                found.filter { it.name.isNotBlank() }
                    .distinctBy { Triple(it.name, Math.round(it.position.lat * 1e4), Math.round(it.position.lon * 1e4)) }
                    .take(MAX_RESULTS),
            )
        } catch (e: IOException) {
            SearchResult.Failed("Couldn’t reach place search (${e.message ?: "network error"}). You may be offline.")
        }
    }

    companion object {
        const val MIN_QUERY = 2
        const val MAX_QUERY = 200
        const val MAX_RESULTS = 5
        const val TIMEOUT_MS = 12_000L
    }
}

/** Android's Geocoder. It is the platform's own service (on phones with Google Play, Google's). */
class GeocoderBackend(private val context: Context, private val make: () -> Geocoder = { Geocoder(context, Locale.getDefault()) }) : PlaceSearchBackend {
    override suspend fun lookup(query: String, max: Int): List<FoundPlace> {
        val geocoder = make()
        val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, max, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.toList())
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resumeWith(Result.failure(IOException(errorMessage ?: "place search failed")))
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, max).orEmpty()
            }
        }
        return addresses.mapNotNull(::toPlace)
    }

    companion object {
        fun isPresent(): Boolean = Geocoder.isPresent()

        /** A readable name and a short "where" line; null when the address has no real position. */
        internal fun toPlace(a: Address): FoundPlace? {
            if (!a.hasLatitude() || !a.hasLongitude()) return null
            val p = LatLon.of(a.latitude, a.longitude) ?: return null
            // featureName is sometimes only a house number; then the first address line reads better.
            val feature = a.featureName?.trim()?.takeIf { it.isNotEmpty() && !it.all { c -> c.isDigit() || c == '-' } }
            val name = (feature ?: a.getAddressLine(0)?.trim() ?: a.locality)?.take(80) ?: return null
            val detail = listOfNotNull(a.locality, a.adminArea, a.countryName)
                .map { it.trim() }.filter { it.isNotEmpty() && !name.contains(it) }.distinct()
                .joinToString(", ").ifEmpty { null }
            return FoundPlace(p, name, detail)
        }
    }
}

/** Places the user already has: waypoints and other trips' stops, nearest first when a position is known. */
object SavedPlaces {
    const val MAX = 40

    data class Entry(val place: FoundPlace, val distanceM: Double?)

    fun list(waypoints: List<Waypoint>, trips: List<Trip>, near: LatLon?, filter: String = "", excludeTripId: String? = null): List<Entry> {
        val all = waypoints.map { FoundPlace(it.position, it.name, "Waypoint") } +
            trips.filter { it.id != excludeTripId }.flatMap { t -> t.stops.map { FoundPlace(it.position, it.name, t.name) } }
        val f = filter.trim()
        return all
            .filter { f.isEmpty() || it.name.contains(f, ignoreCase = true) || it.detail?.contains(f, ignoreCase = true) == true }
            .distinctBy { Triple(it.name.lowercase(), Math.round(it.position.lat * 1e4), Math.round(it.position.lon * 1e4)) }
            .map { Entry(it, near?.let { n -> Geo.distanceM(n, it.position) }) }
            .sortedWith(compareBy(nullsLast<Double>()) { it.distanceM })
            .take(MAX)
    }
}
