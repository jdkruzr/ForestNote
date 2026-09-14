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

    fun loadBookView(deliver: (BookShelfView) -> Unit) {
        worker.execute {
            val value = runCatching {app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("book-view", null)}.getOrNull()
            val mode = BookShelfView.entries.firstOrNull {it.name == value} ?: BookShelfView.LIST
            main.post {deliver(mode)}
        }
    }

    fun saveBookView(mode: BookShelfView) {
        worker.execute {
            runCatching {check(app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("book-view", mode.name).commit())}
                .onFailure {Log.w("BookShelfView", "Could not save book view", it)}
        }
    }

    companion object {
        private const val FILE = "device-ui"
        private const val KEY = "density"
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "device-ui-preferences") }
        private val main = Handler(Looper.getMainLooper())
    }
}
