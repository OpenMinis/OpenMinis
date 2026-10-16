package com.openminis.app

import android.content.Context
import android.util.Log
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.WebAppShortcutRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-perf-async-db-init] Coordinator for asynchronous database initialization.
 *
 * Problem: Room.databaseBuilder().build() is fast, but the FIRST query (any
 * DAO call) triggers SQLiteDatabase.open() + potential migrations on the
 * calling thread. When MinisApp.onCreate called AppDatabase.getInstance()
 * synchronously, the main thread froze for 1-5s on cold start or schema
 * migrations — that is an ANR on slow devices.
 *
 * Solution: defer the database open + repository construction to a background
 * coroutine, and gate every access with `awaitDatabase()`. MainActivity and
 * SessionWorkspace.awaitWarmup() both await this before touching the DAO.
 *
 * Ordering guarantee: NOTHING accesses appContainer.database /
 * appContainer.chatRepository before the deferred is completed, so the
 * migration runs exactly once, on Dispatchers.IO, with zero main-thread cost.
 */
object MinisAppAsyncInit {
    private val databaseDeferred = CompletableDeferred<AppDatabase>()

    /**
     * Start the database initialization on IO dispatcher. Called from
     * MinisApp.onCreate after safe-mode early-return check, before the
     * heavier offload/sandbox subsystems.
     *
     * Also initializes EvolutionEngine AFTER chatRepository is ready,
     * since EvolutionEngine constructor requires chatRepository.
     */
    fun startDatabaseInit(
        context: Context,
        appContainer: com.openminis.app.di.AppContainer,
        memoryRepository: com.openminis.app.data.repository.MemoryRepository,
        providerRepository: com.openminis.app.data.repository.ProviderRepository,
        skillRepository: com.openminis.app.data.repository.SkillRepository,
    ) {
        appContainer.scopes.io.launch {
            try {
                val db = AppDatabase.getInstance(context.applicationContext)
                // Populate the remaining container fields that depend on DB
                appContainer.database = db
                appContainer.chatRepository = ChatRepository(
                    db.chatDao(), db.goalDao(), context.filesDir, db.messageVersionDao(), db,
                )
                appContainer.webAppShortcutRepository = WebAppShortcutRepository(db.webAppShortcutDao())
                databaseDeferred.complete(db)
                Log.i("MinisAppAsyncInit", "database initialized on background thread")

                // Post-init hooks (were scattered in MinisApp.onCreate)
                appContainer.scopes.io.launch {
                    runCatching { appContainer.chatRepository?.dao?.backfillBlankPermissionModesToAsk() }
                        .onFailure { Log.w("MinisAppAsyncInit", "backfill failed", it) }
                }

                // EvolutionEngine requires chatRepository, so initialize it AFTER DB ready
                appContainer.evolutionEngine = runCatching {
                    com.openminis.app.evolution.EvolutionEngine(
                        context = context.applicationContext,
                        memoryRepository = memoryRepository,
                        evolutionDir = java.io.File(context.filesDir, "minis-global/evolution"),
                        chatRepository = appContainer.chatRepository!!,
                        providerRepository = providerRepository,
                        skillRepository = skillRepository,
                    )
                }.onFailure { Log.e("MinisAppAsyncInit", "evolution init failed", it) }.getOrNull()
                com.openminis.app.evolution.EvolutionHooks.engine = appContainer.evolutionEngine
            } catch (t: Throwable) {
                Log.e("MinisAppAsyncInit", "database init failed", t)
                databaseDeferred.completeExceptionally(t)
            }
        }
    }

    /**
     * Await the database to be fully initialized. MainActivity /
     * SessionWorkspace / any code that touches ChatRepository must call this
     * first. Suspends until the IO-thread init completes, or rethrows the
     * failure if init threw.
     */
    suspend fun awaitDatabase(): AppDatabase = databaseDeferred.await()

    /**
     * Non-suspending check: true if the database has successfully initialized.
     * Used by UI guards that want to skip DB-dependent work when safe-mode or
     * init failure has left the database unassigned.
     */
    fun isDatabaseReady(): Boolean = databaseDeferred.isCompleted && !databaseDeferred.isCancelled
}
