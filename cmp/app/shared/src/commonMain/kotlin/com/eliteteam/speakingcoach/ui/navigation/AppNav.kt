package com.eliteteam.speakingcoach.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.eliteteam.speakingcoach.ui.call.CallScreen
import com.eliteteam.speakingcoach.ui.main.MainShell
import com.eliteteam.speakingcoach.ui.review.ReviewScreen
import com.eliteteam.speakingcoach.ui.welcome.WelcomeScreen
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

private val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(WelcomeRoute.serializer())
            subclass(MainRoute.serializer())
            subclass(CallRoute.serializer())
            subclass(ReviewRoute.serializer())
        }
    }
}

@Composable
fun AppNav() {
    val backStack = rememberNavBackStack(navConfig, WelcomeRoute)
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }

    NavDisplay(
        backStack = backStack,
        onBack = { pop(backStack) },
        entryProvider = entryProvider {
            entry<WelcomeRoute> {
                WelcomeScreen(
                    onStart = { openMain(backStack) },
                    onHaveAccount = { openMain(backStack) },
                )
            }
            entry<MainRoute> {
                MainShell(
                    tab = tab,
                    onTabSelected = { tab = it },
                    onStartCall = { backStack.add(CallRoute) },
                    onOpenReview = { backStack.add(ReviewRoute(0)) },
                    onSignOut = {
                        tab = MainTab.Home
                        openWelcome(backStack)
                    },
                )
            }
            entry<CallRoute> {
                CallScreen(
                    onHangup = {
                        pop(backStack)
                        backStack.add(ReviewRoute(0))
                    },
                )
            }
            entry<ReviewRoute> { route ->
                ReviewScreen(
                    stepIndex = route.stepIndex,
                    onBack = { pop(backStack) },
                    onContinue = { isLast ->
                        if (isLast) {
                            popToMain(backStack)
                        } else {
                            backStack.add(ReviewRoute(route.stepIndex + 1))
                        }
                    },
                )
            }
        },
    )
}

private fun pop(backStack: NavBackStack<NavKey>) {
    if (backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }
}

private fun popToMain(backStack: NavBackStack<NavKey>) {
    while (backStack.size > 1 && backStack.lastOrNull() !is MainRoute) {
        backStack.removeAt(backStack.lastIndex)
    }
}

private fun openMain(backStack: NavBackStack<NavKey>) {
    backStack.add(MainRoute)
    while (backStack.size > 1 && backStack.firstOrNull() is WelcomeRoute) {
        backStack.removeAt(0)
    }
}

private fun openWelcome(backStack: NavBackStack<NavKey>) {
    backStack.add(WelcomeRoute)
    while (backStack.size > 1 && backStack.firstOrNull() !is WelcomeRoute) {
        backStack.removeAt(0)
    }
}
