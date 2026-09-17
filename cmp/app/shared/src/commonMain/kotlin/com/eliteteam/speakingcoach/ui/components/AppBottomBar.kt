package com.eliteteam.speakingcoach.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cmp.app.shared.generated.resources.Res
import cmp.app.shared.generated.resources.ic_home
import cmp.app.shared.generated.resources.ic_person
import cmp.app.shared.generated.resources.ic_schedule
import cmp.app.shared.generated.resources.tab_history
import cmp.app.shared.generated.resources.tab_home
import cmp.app.shared.generated.resources.tab_profile
import com.eliteteam.speakingcoach.ui.navigation.MainTab
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        MainTab.entries.forEach { tab ->
            val spec = tab.spec()
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        painter = painterResource(spec.icon),
                        contentDescription = stringResource(spec.label),
                    )
                },
                label = { Text(stringResource(spec.label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        }
    }
}

private data class TabSpec(
    val icon: DrawableResource,
    val label: StringResource,
)

private fun MainTab.spec(): TabSpec = when (this) {
    MainTab.Home -> TabSpec(Res.drawable.ic_home, Res.string.tab_home)
    MainTab.History -> TabSpec(Res.drawable.ic_schedule, Res.string.tab_history)
    MainTab.Profile -> TabSpec(Res.drawable.ic_person, Res.string.tab_profile)
}
