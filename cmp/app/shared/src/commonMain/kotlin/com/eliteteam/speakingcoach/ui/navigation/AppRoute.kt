package com.eliteteam.speakingcoach.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

enum class MainTab {
    Home,
    History,
    Profile,
}

@Serializable
data object WelcomeRoute : NavKey

@Serializable
data object MainRoute : NavKey

@Serializable
data object CallRoute : NavKey

@Serializable
data class ReviewRoute(val stepIndex: Int = 0) : NavKey
