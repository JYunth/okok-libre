package app.okoklibre

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.okoklibre.reminders.ReminderReceiver
import app.okoklibre.ui.AppState
import app.okoklibre.ui.LocalAppState
import app.okoklibre.ui.OkokNavHost
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.theme.OkokLibreTheme
import app.okoklibre.ui.theme.ThemeViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var appState: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appState = AppState.get(this)
        // Held until the analysis is in, so the first frame is already the right person's numbers.
        splash.setKeepOnScreenCondition { appState.bodyData.state.value == null }
        // Only a fresh launch: a recreated activity still holds this intent.
        if (savedInstanceState == null) openReminderTarget(intent)
        setContent {
            CompositionLocalProvider(LocalAppState provides appState) {
                val themeViewModel: ThemeViewModel = appViewModel { ThemeViewModel(it) }
                val theme by themeViewModel.settings.collectAsStateWithLifecycle()
                OkokLibreTheme(theme.mode, theme.personIndex) {
                    Surface(Modifier.fillMaxSize()) {
                        OkokNavHost()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReminderTarget(intent)
    }

    override fun onResume() {
        super.onResume()
        appState.bodyData.refreshDay()
    }

    /** A reminder is for one person, so tapping it shows that person. */
    private fun openReminderTarget(intent: Intent?) {
        val roleId = intent?.getLongExtra(ReminderReceiver.EXTRA_ROLE_ID, -1L) ?: -1L
        intent?.removeExtra(ReminderReceiver.EXTRA_ROLE_ID)
        if (roleId > 0) lifecycleScope.launch { appState.profileSession.switchTo(roleId) }
    }
}
