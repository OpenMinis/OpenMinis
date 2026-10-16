package com.openminis.app

import android.util.Log
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.di.AppContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * [T-perf-async-db-init-v2] Pull the FIRST DAO open (SQLiteDatabase.open +
 * any pending migrations) off the main thread.
 *
 * History: the original PR43 async design moved the instance + repository
 * CONSTRUCTION to Dispatchers.IO too — but Application.onCreate has
 * synchronous, non-suspending readers of `chatRepository` (ConfigRegistry.init,
 * NativeOffloadServer.register, BackgroundTaskNotifier) and onCreate is not a
 * coroutine: those readers can never await a deferred. They read a null
 * container field and every launch died with
 * IllegalStateException("Required value was null.") before the first frame.
 *
 * The construction is therefore synchronous again in MinisApp.onCreate
 * (Room.databaseBuilder().build() is cheap and ChatRepository's constructor
 * only stores DAO references — the SQLiteDatabase open happens on the FIRST
 * DAO call, not at build time). This object now only OWNS that first open:
 * startWarmup touches the DAO once on Dispatchers.IO, so by the time the
 * UI's first query lands the open has either completed or is already in
 * progress under Room's internal lock — a late query blocks for the
 * remaining open time (same as the pre-PR43 synchronous path, bounded by
 * work already started) instead of repeating it.
 */
object MinisAppAsyncInit {
    private const val TAG = "MinisAppAsyncInit"

    private val openDeferred = CompletableDeferred<AppDatabase>()

    /**
     * Force the first DAO open on Dispatchers.IO. Called from
     * MinisApp.onCreate immediately after the repositories are constructed
     * (same thread, so `appContainer.database` is already assigned — no race).
     *
     * Also runs the backfill hook after the open: it is a DAO write, so it
     * belongs on IO after the database file is open, exactly where PR43's
     * post-init hook put it.
     */
    fun startWarmup(appContainer: AppContainer) {
        val db = checkNotNull(appContainer.database)
        appContainer.scopes.io.launch {
            try {
                db.openHelper.writableDatabase
                openDeferred.complete(db)
                Log.i(TAG, "database open completed on background thread")
            } catch (t: Throwable) {
                Log.e(TAG, "database open failed", t)
                openDeferred.completeExceptionally(t)
                return@launch
            }
            runCatching { checkNotNull(appContainer.chatRepository).dao.backfillBlankPermissionModesToAsk() }
                .onFailure { Log.w(TAG, "backfill failed", it) }
        }
    }

    /**
     * Await until the database file is OPEN (open + migrations done), then
     * return the instance. Callers that only need the repository handle can
     * read [AppContainer.chatRepository] directly — it is assigned
     * synchronously in onCreate. Await this before issuing DAO calls from a
     * context that must not block the calling thread on the open.
     */
    suspend fun awaitDatabase(): AppDatabase = openDeferred.await()

    /**
     * Non-suspending check: true if the first open completed. Used by guards
     * that want to skip DAO work when the open failed (corrupt DB quarantine
     * failure) or has not landed yet.
     */
    fun isDatabaseReady(): Boolean = openDeferred.isCompleted && !openDeferred.isCancelled
}
