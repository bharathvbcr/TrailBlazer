package com.example.trailblazer.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trailblazer.AppContainer
import com.example.trailblazer.places.FoundPlace
import com.example.trailblazer.places.SearchResult
import com.example.trailblazer.ui.persist
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the place picker shows for a name search. At most one search is ever running. */
class PlacePickerViewModel(private val c: AppContainer) : ViewModel() {
    /** [places] are the answer to [query]. */
    data class Found(val query: String, val places: List<FoundPlace>)

    data class State(
        val searching: Boolean = false,
        val results: Found? = null,
        /** Something to tell the user about the last search (nothing found, offline, refused), or null. */
        val notice: String? = null,
        /** The query waiting on the user's say-so to be sent online, or null. */
        val askConsent: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * Searches for [query], cancelling the search before it: only the latest search may answer, so a slow earlier
     * one can never replace the results of the one the user asked for after it.
     */
    fun search(query: String, consented: Boolean) {
        job?.cancel()
        _state.value = State(searching = true)
        // Lazy so the job is recorded before it can run to completion.
        val j = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val r = c.placeSearch.search(query, consented)
            ensureActive()
            _state.value = when (r) {
                is SearchResult.Ok -> State(
                    results = Found(query, r.places),
                    notice = if (r.places.isEmpty()) "Nothing found for “$query”." else null,
                )
                SearchResult.NotConsented -> State(askConsent = query)
                SearchResult.NotAvailable -> State(notice = "Place search isn’t available on this phone. Paste coordinates or a map link instead.")
                is SearchResult.Failed -> State(notice = r.message)
                is SearchResult.Refused -> State(notice = r.message)
            }
        }
        job = j
        j.start()
    }

    /** The user said yes to online search for [query]: remember that, and run it. */
    fun agreeAndSearch(query: String) {
        c.persist { it.copy(placeSearchConsent = true) }
        search(query, consented = true)
    }

    fun declineConsent() {
        _state.update { State(notice = "Search is off. Paste coordinates or a map link, or pick a saved place.") }
    }

    /** Drops the answer and any search in flight (the text changed, or the picker was closed). */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = State()
    }
}
