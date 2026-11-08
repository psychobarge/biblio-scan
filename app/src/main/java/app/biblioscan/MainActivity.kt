package app.biblioscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.biblioscan.ui.BookScreen
import app.biblioscan.ui.CaptureScreen
import app.biblioscan.ui.LibraryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Three screens: "capture", "library", or a book id.
                var screen by rememberSaveable { mutableStateOf("capture") }
                when (screen) {
                    "capture" -> CaptureScreen(onOpenLibrary = { screen = "library" })
                    "library" -> {
                        BackHandler { screen = "capture" }
                        LibraryScreen(onBack = { screen = "capture" }, onOpen = { screen = it })
                    }
                    else -> {
                        BackHandler { screen = "library" }
                        BookScreen(id = screen, onBack = { screen = "library" })
                    }
                }
            }
        }
    }
}
