package com.eliteteam.speakingcoach.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.eliteteam.speakingcoach.data.SessionStore
import com.eliteteam.speakingcoach.ui.auth.AuthScreen
import com.eliteteam.speakingcoach.ui.call.CallScreen
import com.eliteteam.speakingcoach.ui.home.HomeScreen
import com.eliteteam.speakingcoach.ui.profile.ProfileScreen
import com.eliteteam.speakingcoach.ui.review.ReviewScreen
import com.eliteteam.speakingcoach.ui.welcome.WelcomeScreen
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.koin.compose.koinInject

private val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(WelcomeRoute.serializer())
            subclass(AuthRoute.serializer())
            subclass(MainRoute.serializer())
            subclass(ProfileRoute.serializer())
            subclass(CallRoute.serializer())
            subclass(ReviewRoute.serializer())
        }
    }
}

@Composable
fun AppNav(
    sessionStore: SessionStore = koinInject(),
) {
    val session by sessionStore.session.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(navConfig, WelcomeRoute)

    LaunchedEffect(session) {
        if (session != null) {
            openMain(backStack)
        } else {
            openWelcome(backStack)
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { pop(backStack) },
        entryProvider = entryProvider {
            entry<WelcomeRoute> {
                WelcomeScreen(
                    onStart = { backStack.add(AuthRoute(register = true)) },
                    onHaveAccount = { backStack.add(AuthRoute(register = false)) },
                )
            }
            entry<AuthRoute> { route ->
                AuthScreen(
                    register = route.register,
                    onBack = { pop(backStack) },
                )
            }
            entry<MainRoute> {
                HomeScreen(
                    onStart = { sessionId -> backStack.add(CallRoute(sessionId)) },
                    onProfile = { backStack.add(ProfileRoute) },
                    onLastConversation = { backStack.add(ReviewRoute(0)) },
                )
            }
            entry<ProfileRoute> {
                ProfileScreen()
            }
            entry<CallRoute> { route ->
                CallScreen(
                    sessionId = route.sessionId,
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
    if (backStack.lastOrNull() is MainRoute) return
    if (backStack.any { it is MainRoute }) {
        while (backStack.size > 1 && backStack.lastOrNull() !is MainRoute) {
            backStack.removeAt(backStack.lastIndex)
        }
        return
    }
    backStack.add(MainRoute)
    while (backStack.size > 1 && backStack.firstOrNull() is WelcomeRoute) {
        backStack.removeAt(0)
    }
    while (backStack.size > 1 && backStack.firstOrNull() is AuthRoute) {
        backStack.removeAt(0)
    }
}

private fun openWelcome(backStack: NavBackStack<NavKey>) {
    if (backStack.lastOrNull() is WelcomeRoute && backStack.size == 1) return
    backStack.add(WelcomeRoute)
    while (backStack.size > 1 && backStack.firstOrNull() !is WelcomeRoute) {
        backStack.removeAt(0)
    }
}
