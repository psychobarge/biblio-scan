package app.biblioscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.biblioscan.ui.BiblioTheme
import app.biblioscan.ui.BookScreen
import app.biblioscan.ui.CaptureScreen
import app.biblioscan.ui.InboxScreen
import app.biblioscan.ui.LibraryScreen
import app.biblioscan.ui.PlaceScreen
import app.biblioscan.ui.PlacesScreen
import app.biblioscan.ui.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// Tabs (SPEC-8 §2)
@Serializable object LibraryRoute
@Serializable object InboxRoute
@Serializable object PlacesRoute

// Full screen, no tab bar
@Serializable object CaptureRoute
@Serializable object SettingsRoute
/** [scope]: a place id or Places.NONE. */
@Serializable data class PlaceRoute(val scope: String)
/** reviewPos > 0 = review mode, showing "reviewPos / reviewTotal"; placeId = default place carried along the review. */
@Serializable data class BookRoute(val id: String, val reviewPos: Int = 0, val reviewTotal: Int = 0, val placeId: String? = null)

/** Standard tab switch: one copy of each tab, its state kept. */
private fun NavHostController.tab(route: Any) = navigate(route) {
    popUpTo<LibraryRoute> { saveState = true }
    launchSingleTop = true
    restoreState = true
}

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
                // Shared so a message survives navigation (e.g. "Revue terminée" shown on the list).
                val snackbar = remember { SnackbarHostState() }
                val toValidate by app.db.books().toValidateCount().collectAsStateWithLifecycle(0)
                val dest = nav.currentBackStackEntryAsState().value?.destination
                val tabs = listOf(LibraryRoute, InboxRoute, PlacesRoute)
                val current = tabs.firstOrNull { t -> dest?.hasRoute(t::class) == true }

                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (current != null) NavigationBar {
                            NavigationBarItem(
                                selected = current == LibraryRoute, onClick = { nav.tab(LibraryRoute) },
                                icon = { Icon(painterResource(R.drawable.ic_library), null) }, label = { Text("Bibliothèque") },
                            )
                            NavigationBarItem(
                                selected = current == InboxRoute, onClick = { nav.tab(InboxRoute) },
                                icon = {
                                    BadgedBox(badge = { if (toValidate > 0) Badge { Text("$toValidate") } }) {
                                        Icon(Icons.Default.CheckCircle, null)
                                    }
                                },
                                label = { Text("À valider") },
                            )
                            NavigationBarItem(
                                selected = current == PlacesRoute, onClick = { nav.tab(PlacesRoute) },
                                icon = { Icon(Icons.Default.Place, null) }, label = { Text("Rangements") },
                            )
                        }
                    },
                ) { padding ->
                    // Screens keep their own Scaffold: consume the bar's height so they don't pad twice.
                    NavHost(nav, startDestination = LibraryRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                        composable<LibraryRoute> {
                            LibraryScreen(
                                snackbar = snackbar,
                                onScan = { nav.navigate(CaptureRoute) },
                                onOpen = { nav.navigate(BookRoute(it)) },
                                onInbox = { nav.tab(InboxRoute) },
                                onSettings = { nav.navigate(SettingsRoute) },
                            )
                        }
                        composable<InboxRoute> {
                            InboxScreen(
                                snackbar = snackbar,
                                onOpen = { nav.navigate(BookRoute(it)) },
                                onReview = { id, total -> nav.navigate(BookRoute(id, 1, total)) },
                            )
                        }
                        composable<PlacesRoute> { PlacesScreen(onOpenPlace = { nav.navigate(PlaceRoute(it)) }) }
                        composable<PlaceRoute> { entry ->
                            PlaceScreen(
                                scope = entry.toRoute<PlaceRoute>().scope,
                                snackbar = snackbar,
                                onBack = { nav.popBackStack() },
                                onOpen = { nav.navigate(BookRoute(it)) },
                            )
                        }
                        composable<SettingsRoute> { SettingsScreen(snackbar = snackbar, onBack = { nav.popBackStack() }) }
                        composable<CaptureRoute> {
                            CaptureScreen(
                                onDone = { nav.popBackStack() },
                                // The new books wait in « À valider ».
                                onFinish = { nav.popBackStack(); nav.tab(InboxRoute) },
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
}
