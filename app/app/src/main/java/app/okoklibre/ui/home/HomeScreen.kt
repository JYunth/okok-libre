package app.okoklibre.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.okoklibre.R
import app.okoklibre.ui.Routes
import app.okoklibre.ui.design.Hairline
import app.okoklibre.ui.history.HistoryScreen
import app.okoklibre.ui.settings.YouScreen
import app.okoklibre.ui.theme.LocalAppColors
import app.okoklibre.ui.today.TodayScreen
import app.okoklibre.ui.trends.TrendsScreen

private enum class Tab(val label: Int, val icon: ImageVector) {
    TODAY(R.string.tab_today, Icons.Outlined.WbSunny),
    TRENDS(R.string.tab_trends, Icons.Outlined.ShowChart),
    HISTORY(R.string.tab_history, Icons.Outlined.CalendarMonth),
    YOU(R.string.tab_you, Icons.Outlined.Person),
}

/** The four places in the app: a bar on phones, a rail on anything wider. */
@Composable
fun HomeScreen(navController: NavHostController) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val holder = rememberSaveableStateHolder()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { if (!wide) BottomTabs(tab) { tab = it } },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = tab == Tab.TODAY,
                    enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
                    exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.92f),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { navController.navigate(Routes.MEASURE) { launchSingleTop = true } },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(stringResource(R.string.weigh_in), style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                if (wide) SideTabs(tab) { tab = it }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { fadeIn(tween(180, 60)) togetherWith fadeOut(tween(90)) },
                        label = "tab",
                        modifier = Modifier.widthIn(max = 640.dp),
                    ) { current ->
                        holder.SaveableStateProvider(current.name) {
                            when (current) {
                                Tab.TODAY -> TodayScreen(navController)
                                Tab.TRENDS -> TrendsScreen(navController)
                                Tab.HISTORY -> HistoryScreen(navController)
                                Tab.YOU -> YouScreen(navController)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = LocalAppColors.current
    Column {
        Hairline()
        NavigationBar(containerColor = colors.ground, tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.ink,
                        selectedTextColor = colors.ink,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = colors.muted,
                        unselectedTextColor = colors.muted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SideTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = LocalAppColors.current
    NavigationRail(containerColor = colors.ground) {
        Tab.entries.forEach { tab ->
            NavigationRailItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.ink,
                    selectedTextColor = colors.ink,
                    indicatorColor = colors.raised,
                    unselectedIconColor = colors.muted,
                    unselectedTextColor = colors.muted,
                ),
            )
        }
    }
}
