package com.example.trailblazer.ui

import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Stores a setting from a screen's ViewModel. It runs on the app's own scope, not the ViewModel's or the
 * composition's: a write the user just made must reach disk even when they leave the screen (which clears its
 * ViewModel and cancels its scope) before the store has finished.
 */
internal fun AppContainer.persist(transform: (Settings) -> Settings): Job = scope.launch { prefs.update(transform) }
