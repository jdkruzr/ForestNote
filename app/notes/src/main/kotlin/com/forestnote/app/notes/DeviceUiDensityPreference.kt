package com.forestnote.app.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** Private app preferences, excluded from the shared database and library exports. */
internal class DeviceUiDensityPreference(context: Context) {
    private val app = context.applicationContext
    fun load(deliver: (UiDensity) -> Unit) {
        worker.execute {
            val mode = runCatching {
                LibraryDensityPolicy.parse(app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
            }.getOrDefault(UiDensity.AUTO)
            main.post { deliver(mode) }
        }
    }

    fun save(mode: UiDensity) {
        // Ordered, off-main, and not cancelled just because the Library overlay closes.
        worker.execute {
            runCatching {
                check(app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).commit())
            }.onFailure { Log.w("LibraryDensity", "Could not save device UI density", it) }
        }
    }

    fun loadBookView(deliver: (LibraryShelfView) -> Unit) = loadView("book-view", LibraryShelfView.LIST, deliver)
    fun loadNotebookView(deliver: (LibraryShelfView) -> Unit) = loadView("notebook-view", LibraryShelfView.TILES, deliver)
    fun saveBookView(mode: LibraryShelfView) = saveView("book-view", mode)
    fun saveNotebookView(mode: LibraryShelfView) = saveView("notebook-view", mode)

    private fun loadView(key: String, fallback: LibraryShelfView, deliver: (LibraryShelfView) -> Unit) {
        worker.execute {
            val value = runCatching {app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, null)}.getOrNull()
            val mode = LibraryShelfView.entries.firstOrNull {it.name == value} ?: fallback
            main.post {deliver(mode)}
        }
    }

    private fun saveView(key: String, mode: LibraryShelfView) {
        worker.execute {
            runCatching {check(app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, mode.name).commit())}
                .onFailure {Log.w("LibraryShelfView", "Could not save $key", it)}
        }
    }

    companion object {
        private const val FILE = "device-ui"
        private const val KEY = "density"
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "device-ui-preferences") }
        private val main = Handler(Looper.getMainLooper())
    }
}
