package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig

/** One unit of work for the repository's single persistence worker. */
internal sealed class ProviderPersistRequest {
    /** Persist [config] (a detached snapshot) and, if nothing newer has been queued, publish its canonical form. */
    data class Save(val config: ProviderConfig, val generation: Long) : ProviderPersistRequest()

    /** Completes [done] once every save queued before it has reached the store. */
    class Flush(val done: kotlinx.coroutines.CompletableDeferred<Unit>) : ProviderPersistRequest()

    /**
     * [T-android-save-off-main-thread] Any other provider.db write that must not run
     * on the caller's thread (custom thinking rules, the rule-cache warm). Queued on
     * the same single worker so it stays ordered with config saves.
     */
    class Task(val block: suspend () -> Unit) : ProviderPersistRequest()
}

/**
 * [T-android-save-off-main-thread] Non-blocking entry point used by every
 * read-modify-write mutator: publish the new state in memory under
 * [ProviderRepository.configLock] and hand the durable part (serialize →
 * 5-table `replaceAll` → `prefs.commit()`) to the repository's single
 * persistence worker.
 *
 * [T-android-provider-room-store] Double-write: DB + legacy JSON mirror, both
 * produced inside the same worker step. The mirror keeps older app builds able
 * to read current config on downgrade; the DB is the authoritative store on
 * this build.
 *
 * Serialization is preserved, not weakened: mutators still hold `configLock`
 * for their whole body, so snapshots are taken one at a time, and the worker
 * consumes ONE FIFO queue on ONE coroutine, so persists never interleave and
 * never run against a list another writer is mutating. The fresh
 * `.copy(…toMutableList())` wrapper alone is not enough — data-class structural
 * equals walks the inner Lists, so T273 bumps `revision` on every publish and
 * 18+ `collectAsState` callers always see the new value.
 *
 * The publish order changed on purpose. The old body persisted first and only
 * then emitted, which is what made the caller (a Compose click handler) wait
 * for the disk. Publishing first is also what keeps read-modify-write honest:
 * a mutator that runs while a save is in flight must build on the value the
 * previous mutator just set, not on the stale pre-write snapshot — otherwise
 * two quick slot picks lose the first one. Durability is still ordered (the
 * worker serializes each snapshot from the queue and `replaceAll` is a full
 * replace, so any later successful save re-lands everything a failed one
 * dropped). Callers that must not return before the write is on disk await
 * [ProviderRepository.awaitPersisted].
 */
internal fun ProviderRepository.saveConfig(config: ProviderConfig) {
    // [T-android-provider-empty-load-wipe] No "block empty saves" guard here on
    // purpose: mutators pass the SAME object as _config.value and mutate it in
    // place, so at this point an empty `config` and an empty `_config.value`
    // are the same list — a legitimate "user deleted their last provider" is
    // indistinguishable from a phantom-empty load. The wipe is prevented at the
    // source instead (loadConfigSuspending refuses to return a blank config
    // when the DB read failed rather than honestly reporting zero rows).
    val generation = saveGeneration.incrementAndGet()
    val detached = config.detachedCopy(revision = ProviderConfig.nextRevision())
    synchronized(configLock) {
        _config.value = detached
        ensurePersistWorker()
        // Unbounded channel: send() never suspends, so the caller leaves with
        // no I/O on its thread. The snapshot handed over is detached above, so
        // the mutator's next workingCopy() cannot touch what the worker encodes.
        persistRequests.trySend(ProviderPersistRequest.Save(detached, generation))
    }
}

/**
 * Blocking variant kept for non-UI callers that already live on an IO
 * dispatcher and need the write durable before returning (cold-start JSON→DB
 * import, tests). Same serialize + publish contract as the old inline path.
 */
internal suspend fun ProviderRepository.saveConfigSuspending(config: ProviderConfig): ProviderConfig {
    val generation = saveGeneration.incrementAndGet()
    val detached = config.detachedCopy()
    val canonical = persistDetached(detached)
    publishIfNewest(generation, canonical)
    return canonical
}

/**
 * Serialize + write the snapshot to (DB) + (JSON mirror).
 *
 * Deliberately NOT wrapped in `configLock`: [persistToDbAndMirror] suspends on
 * the Room transaction, and holding a JVM monitor across a suspension point
 * would park every mutator — including the Compose click handlers this whole
 * change exists to unblock — for the duration of the write. The lock's job
 * (never encode a list another writer is mutating) is now carried by the
 * detached snapshot the caller hands over.
 */
private suspend fun ProviderRepository.persistDetached(snapshot: ProviderConfig): ProviderConfig =
    persistToDbAndMirror(snapshot)

/**
 * Re-publish the canonicalized (composite entry-id) form of [snapshot], but only
 * while it is still the newest save. A stale canonical would otherwise clobber a
 * mutator's change that is already live in memory. The check and the publish are
 * atomic because every mutator holds [ProviderRepository.configLock] for its
 * whole body.
 */
internal fun ProviderRepository.publishIfNewest(generation: Long, canonical: ProviderConfig) {
    synchronized(configLock) {
        if (generation == saveGeneration.get()) {
            _config.value = canonical.withDetachedLists(ProviderConfig.nextRevision())
        }
    }
}

/**
 * The persistence worker. Consumed by exactly one coroutine on
 * [ProviderRepository.writeScope], so persists are serialised with each other
 * while staying off the caller's thread.
 */
internal suspend fun ProviderRepository.drainPersistQueue() {
    for (request in persistRequests) {
        when (request) {
            is ProviderPersistRequest.Save -> {
                val canonical = try {
                    persistDetached(request.config)
                } catch (e: Exception) {
                    // In memory the change is live but the store rejected it
                    // (DB locked / disk full / prefs corruption). Keep it
                    // observable, keep serving from memory, and let the NEXT
                    // save — which rewrites every table — resync the store.
                    android.util.Log.e(
                        "ProviderRepo",
                        "[ProviderStore] async persist failed; in-memory config is ahead of the store " +
                            "and will be re-landed by the next successful save: ${e.message}",
                        e,
                    )
                    continue
                }
                publishIfNewest(request.generation, canonical)
            }
            is ProviderPersistRequest.Flush -> request.done.complete(Unit)
            is ProviderPersistRequest.Task -> request.block()
        }
    }
}

/**
 * A copy that shares NO mutable state with [this]: the published value is what
 * Compose collectors iterate, and the queued snapshot is what the worker JSON-
 * encodes — neither may alias a list a later mutator appends to
 * ([T-android-provider-emitted-list-cow]).
 */
private fun ProviderConfig.detachedCopy(revision: Long = this.revision): ProviderConfig =
    withDetachedLists(revision)

private fun ProviderConfig.withDetachedLists(revision: Long): ProviderConfig = copy(
    instances = instances.toMutableList(),
    modelEntries = modelEntries.toMutableList(),
    // Deep-copy the groups: ModelGroup.memberEntryIds is a MutableList that
    // removeEntry/removeGroup edit in place.
    modelGroups = modelGroups
        .map { g -> g.copy(memberEntryIds = g.memberEntryIds.toMutableList()) }
        .toMutableList(),
    agentLoopModelEntryIds = agentLoopModelEntryIds.toMutableList(),
    agentLoopGroupIds = agentLoopGroupIds.toMutableList(),
    revision = revision,
)
