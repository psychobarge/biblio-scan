package app.biblioscan

import android.app.Application
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.CoroutineExceptionHandler
import app.biblioscan.data.AppDb
import app.biblioscan.data.Books
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BiblioApp : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDb::class.java, "biblio.db").build() }

    /** App-lifetime scope: work launched here (e.g. saving a capture) outlives any screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        // Don't crash: a photo already on disk gets its row back from reconcile() at next start.
        Log.e("BiblioScan", "background task failed", e)
    })

    override fun onCreate() {
        super.onCreate()
        scope.launch { Books.reconcile(this@BiblioApp) }
    }
}

val android.content.Context.app get() = applicationContext as BiblioApp
