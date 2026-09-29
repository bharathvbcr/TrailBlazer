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
@Serializable data class TripEditRoute(val tripId: String? = null) : Route
@Serializable data class TrackDetailRoute(val trackId: String) : Route
@Serializable data object LevelRoute : Route
@Serializable data object AltimeterRoute : Route
@Serializable data object SosRoute : Route
@Serializable data object DiagnosticsRoute : Route
@Serializable data object SightingRoute : Route

val TopLevel: List<Route> = listOf(NowRoute, SkyRoute, TripsRoute, ToolsRoute)
