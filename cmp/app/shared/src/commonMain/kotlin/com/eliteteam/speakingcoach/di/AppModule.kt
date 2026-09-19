package com.eliteteam.speakingcoach.di

import co.touchlab.kermit.koin.getLoggerWithTag
import com.eliteteam.speakingcoach.data.HttpSpeakingCoachClient
import com.eliteteam.speakingcoach.data.SessionStore
import com.eliteteam.speakingcoach.data.SpeakingCoachClient
import com.eliteteam.speakingcoach.data.apiBaseUrl
import com.eliteteam.speakingcoach.data.createHttpClient
import com.eliteteam.speakingcoach.ui.auth.AuthViewModel
import com.eliteteam.speakingcoach.ui.call.CallViewModel
import com.eliteteam.speakingcoach.ui.home.HomeViewModel
import com.eliteteam.speakingcoach.ui.mock.DailyGoalStore
import com.eliteteam.speakingcoach.ui.profile.ProfileViewModel
import com.eliteteam.speakingcoach.ui.review.ReviewViewModel
import com.russhwolf.settings.Settings
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { Settings() }
    single { SessionStore(get(), getLoggerWithTag("SessionStore")) }
    single { createHttpClient() }
    single<SpeakingCoachClient> {
        HttpSpeakingCoachClient(
            http = get(),
            sessionStore = get(),
            logger = getLoggerWithTag("HttpSpeakingCoachClient"),
            baseUrl = apiBaseUrl(),
        )
    }
    single { DailyGoalStore() }
    viewModel { parameters ->
        AuthViewModel(
            client = get(),
            sessionStore = get(),
            register = parameters.get(),
            logger = getLoggerWithTag("AuthViewModel"),
        )
    }
    viewModel { HomeViewModel(get(), get(), get(), getLoggerWithTag("HomeViewModel")) }
    viewModel { parameters ->
        CallViewModel(
            sessionId = parameters.get(),
            logger = getLoggerWithTag("CallViewModel"),
        )
    }
    viewModel { ReviewViewModel(getLoggerWithTag("ReviewViewModel")) }
    viewModel { ProfileViewModel(get(), get(), get(), getLoggerWithTag("ProfileViewModel")) }
}
