package app.biblioscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.biblioscan.ui.BiblioTheme
import app.biblioscan.ui.BookScreen
import app.biblioscan.ui.CaptureScreen
import app.biblioscan.ui.LibraryScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable object CaptureRoute

/** reviewPos > 0 = review mode, showing "reviewPos / reviewTotal". */
@Serializable data class BookRoute(val id: String, val reviewPos: Int = 0, val reviewTotal: Int = 0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the splash only until the library has loaded once: no empty-state flash.
        var loaded = false
        splash.setKeepOnScreenCondition { !loaded }
        lifecycleScope.launch { app.db.books().all().first(); loaded = true }

        setContent {
            BiblioTheme {
                val nav = rememberNavController()
                // Shared so a message survives navigation (e.g. "Revue terminée" shown on the library).
                val snackbar = remember { SnackbarHostState() }
                NavHost(nav, startDestination = LibraryRoute) {
                    composable<LibraryRoute> {
                        LibraryScreen(
                            snackbar = snackbar,
                            onScan = { nav.navigate(CaptureRoute) },
                            onOpen = { nav.navigate(BookRoute(it)) },
                            onReview = { id, total -> nav.navigate(BookRoute(id, 1, total)) },
                        )
                    }
                    composable<CaptureRoute> {
                        CaptureScreen(
                            onDone = { nav.popBackStack() },
                            onOpenBook = { nav.navigate(BookRoute(it)) },
                        )
                    }
                    composable<BookRoute> { entry ->
                        val route = entry.toRoute<BookRoute>()
                        BookScreen(
                            route = route,
                            snackbar = snackbar,
                            onBack = { nav.popBackStack() },
                            onNext = { next ->
                                nav.navigate(next) { popUpTo<BookRoute> { inclusive = true } }
                            },
                        )
                    }
                }
            }
        }
    }
}
