package com.example.trailblazer.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Navigation keys. Tabs are the four roots; everything else is pushed on top of a tab. */
@Serializable sealed interface Route : NavKey

@Serializable data object NowRoute : Route
@Serializable data object SkyRoute : Route
@Serializable data object TripsRoute : Route
@Serializable data object ToolsRoute : Route

@Serializable data object SettingsRoute : Route
@Serializable data class DocsRoute(val file: String? = null) : Route
/** A place handed to the trip editor to add on open (from a share); [name] null when it has none. */
@Serializable data class SeedPlace(val lat: Double, val lon: Double, val name: String? = null)

/** Edit a trip, or start one when [tripId] is null; [seed] places are added once the trip has loaded. */
@Serializable data class TripEditRoute(val tripId: String? = null, val seed: List<SeedPlace> = emptyList()) : Route

/** Text another app shared to TrailBlazer (a map link, coordinates, a place name), to add to a trip. */
@Serializable data class ShareRoute(val text: String) : Route
@Serializable data class TrackDetailRoute(val trackId: String) : Route
@Serializable data object LevelRoute : Route
@Serializable data object AltimeterRoute : Route
@Serializable data object SosRoute : Route
@Serializable data object DiagnosticsRoute : Route
@Serializable data object SightingRoute : Route

/** Stargazing for a given place (from the Sky tab), or for the live position when no place is given. */
@Serializable data class StargazeRoute(val lat: Double? = null, val lon: Double? = null, val label: String? = null) : Route

val TopLevel: List<Route> = listOf(NowRoute, SkyRoute, TripsRoute, ToolsRoute)
