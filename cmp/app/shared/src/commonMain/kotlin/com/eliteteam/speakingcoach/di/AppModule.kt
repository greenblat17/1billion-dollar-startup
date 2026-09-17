package com.eliteteam.speakingcoach.di

import com.eliteteam.speakingcoach.ui.call.CallViewModel
import com.eliteteam.speakingcoach.ui.history.HistoryViewModel
import com.eliteteam.speakingcoach.ui.home.HomeViewModel
import com.eliteteam.speakingcoach.ui.profile.ProfileViewModel
import com.eliteteam.speakingcoach.ui.review.ReviewViewModel
import org.koin.dsl.module
import org.koin.plugin.module.dsl.viewModel

val appModule = module {
    viewModel<HomeViewModel>()
    viewModel<CallViewModel>()
    viewModel<ReviewViewModel>()
    viewModel<HistoryViewModel>()
    viewModel<ProfileViewModel>()
}
