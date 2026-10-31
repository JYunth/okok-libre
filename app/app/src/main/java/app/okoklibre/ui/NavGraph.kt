package app.okoklibre.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.okoklibre.ui.compare.CompareScreen
import app.okoklibre.ui.devices.DeviceBindScreen
import app.okoklibre.ui.devices.DevicesScreen
import app.okoklibre.ui.diagnostics.DiagnosticsScreen
import app.okoklibre.ui.family.PeopleScreen
import app.okoklibre.ui.family.RoleEditScreen
import app.okoklibre.ui.goals.GoalHistoryScreen
import app.okoklibre.ui.goals.GoalsScreen
import app.okoklibre.ui.home.HomeScreen
import app.okoklibre.ui.measure.MeasureScreen
import app.okoklibre.ui.onboarding.OnboardingScreen
import app.okoklibre.ui.reading.ReadingScreen
import app.okoklibre.ui.reminders.ReminderEditScreen
import app.okoklibre.ui.reminders.RemindersScreen
import app.okoklibre.ui.settings.AboutScreen
import app.okoklibre.ui.settings.AppearanceScreen
import app.okoklibre.ui.settings.DataScreen
import app.okoklibre.ui.settings.UnitsScreen
import app.okoklibre.ui.tmpweights.TmpWeightsScreen
import kotlinx.coroutines.flow.first

/** All navigation routes. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val MEASURE = "measure"
    const val DEVICES = "devices"
    const val DEVICE_BIND = "deviceBind"
    const val PEOPLE = "people"
    const val UNITS = "units"
    const val APPEARANCE = "appearance"
    const val REMINDERS = "reminders"
    const val GOALS = "goals"
    const val GOAL_HISTORY = "goalHistory"
    const val DATA = "data"
    const val ABOUT = "about"
    const val DIAGNOSTICS = "diagnostics"
    const val TMP_WEIGHTS = "tmpWeights"

    const val ROLE_EDIT = "roleEdit/{roleId}"
    const val REMINDER_EDIT = "reminderEdit/{reminderId}"
    const val READING = "reading/{recordId}?fresh={fresh}"
    const val COMPARE = "compare/{a}/{b}"

    /** roleId -1 = create a new person. */
    fun roleEdit(roleId: Long = -1L) = "roleEdit/$roleId"

    /** reminderId -1 = create a new reminder. */
    fun reminderEdit(reminderId: Long = -1L) = "reminderEdit/$reminderId"

    /** [fresh] marks a reading that was just weighed, so ownership stays front and centre. */
    fun reading(recordId: Long, fresh: Boolean = false) = "reading/$recordId?fresh=$fresh"

    fun compare(a: Long, b: Long) = "compare/$a/$b"
}

private const val TRANSITION_MS = 260

/** Screens slide a short way and fade: enough to show direction, never a page-turn. */
@Composable
fun OkokNavHost() {
    val appState = LocalAppState.current
    val onboardingDone by produceState<Boolean?>(initialValue = null) {
        value = appState.settingsRepository.onboardingDone.first()
    }
    val done = onboardingDone ?: return
    val navController = rememberNavController()
    val back = navController::popBackStackSafely

    NavHost(
        navController = navController,
        startDestination = if (done) Routes.HOME else Routes.ONBOARDING,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideInHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { it / 12 } +
                fadeIn(tween(TRANSITION_MS))
        },
        exitTransition = {
            slideOutHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { -it / 24 } +
                fadeOut(tween(TRANSITION_MS / 2))
        },
        popEnterTransition = {
            slideInHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { -it / 24 } +
                fadeIn(tween(TRANSITION_MS))
        },
        popExitTransition = {
            slideOutHorizontally(tween(TRANSITION_MS, easing = FastOutSlowInEasing)) { it / 12 } +
                fadeOut(tween(TRANSITION_MS / 2))
        },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) { HomeScreen(navController) }

        composable(Routes.MEASURE) {
            MeasureScreen(
                onBack = back,
                onAddDevice = { navController.navigate(Routes.DEVICE_BIND) },
                onSaved = { recordId ->
                    navController.navigate(Routes.reading(recordId, fresh = true)) {
                        popUpTo(Routes.MEASURE) { inclusive = true }
                    }
                },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(
            Routes.READING,
            arguments = listOf(
                navArgument("recordId") { type = NavType.LongType },
                navArgument("fresh") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            ReadingScreen(
                recordId = entry.arguments?.getLong("recordId") ?: 0L,
                fresh = entry.arguments?.getBoolean("fresh") ?: false,
                onBack = back,
                onMoved = { newId ->
                    navController.navigate(Routes.reading(newId)) {
                        popUpTo(Routes.READING) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.COMPARE,
            arguments = listOf(
                navArgument("a") { type = NavType.LongType },
                navArgument("b") { type = NavType.LongType },
            ),
        ) { entry ->
            CompareScreen(
                firstId = entry.arguments?.getLong("a") ?: 0L,
                secondId = entry.arguments?.getLong("b") ?: 0L,
                onBack = back,
            )
        }

        composable(Routes.DEVICES) {
            DevicesScreen(onAdd = { navController.navigate(Routes.DEVICE_BIND) }, onBack = back)
        }
        composable(Routes.DEVICE_BIND) { DeviceBindScreen(onBound = back, onBack = back) }

        composable(Routes.PEOPLE) {
            PeopleScreen(onEditRole = { navController.navigate(Routes.roleEdit(it)) }, onBack = back)
        }
        composable(
            Routes.ROLE_EDIT,
            arguments = listOf(navArgument("roleId") { type = NavType.LongType }),
        ) { entry ->
            RoleEditScreen(roleId = entry.arguments?.getLong("roleId") ?: -1L, onDone = back)
        }

        composable(Routes.UNITS) { UnitsScreen(onBack = back) }
        composable(Routes.APPEARANCE) { AppearanceScreen(onBack = back) }
        composable(Routes.DATA) { DataScreen(onBack = back) }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = back, onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) })
        }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = back) }

        composable(Routes.REMINDERS) {
            RemindersScreen(onEdit = { navController.navigate(Routes.reminderEdit(it)) }, onBack = back)
        }
        composable(
            Routes.REMINDER_EDIT,
            arguments = listOf(navArgument("reminderId") { type = NavType.LongType }),
        ) { entry ->
            ReminderEditScreen(reminderId = entry.arguments?.getLong("reminderId") ?: -1L, onDone = back)
        }

        composable(Routes.GOALS) {
            GoalsScreen(onHistory = { navController.navigate(Routes.GOAL_HISTORY) }, onBack = back)
        }
        composable(Routes.GOAL_HISTORY) { GoalHistoryScreen(onBack = back) }

        composable(Routes.TMP_WEIGHTS) {
            TmpWeightsScreen(onBack = back, onOpenRecord = { navController.navigate(Routes.reading(it)) })
        }
    }
}

private fun NavHostController.popBackStackSafely() {
    if (!popBackStack()) navigate(Routes.HOME)
}
