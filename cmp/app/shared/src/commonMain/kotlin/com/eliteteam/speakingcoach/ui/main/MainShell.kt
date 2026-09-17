package com.eliteteam.speakingcoach.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eliteteam.speakingcoach.ui.components.AppBottomBar
import com.eliteteam.speakingcoach.ui.history.HistoryScreen
import com.eliteteam.speakingcoach.ui.home.HomeScreen
import com.eliteteam.speakingcoach.ui.navigation.MainTab
import com.eliteteam.speakingcoach.ui.profile.ProfileScreen

@Composable
fun MainShell(
    tab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    onStartCall: () -> Unit,
    onOpenReview: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AppBottomBar(selected = tab, onSelect = onTabSelected)
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (tab) {
            MainTab.Home -> HomeScreen(
                onStart = onStartCall,
                onProfile = { onTabSelected(MainTab.Profile) },
                onLastConversation = onOpenReview,
                modifier = contentModifier,
            )
            MainTab.History -> HistoryScreen(
                onItemClick = { onOpenReview() },
                modifier = contentModifier,
            )
            MainTab.Profile -> ProfileScreen(
                onSignOut = onSignOut,
                modifier = contentModifier,
            )
        }
    }
}
