package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupExporter
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupZip
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-backup-subagents] Debug-only drivers for backup / restore and the
 * sub agent roster, so a cross-platform backup round trip can be exercised on a
 * device without the UI.
 *
 * They call the same [BackupExporter] / [BackupImporter] the Backup screen
 * uses; only the trigger differs. What they deliberately do NOT do:
 *  - deliver a package to a destination: the UI's only path uploads to the
 *    user's configured servers, which a test must not touch;
 *  - include credentials: `includeCredentials` defaults to false, so a test
 *    package carries no API keys or tokens.
 */
internal object BackupDebugMethods {

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp ?: throw RPCException(-32000, "MinisApp not initialized")

    private fun repo(context: Context): ProviderRepository {
        val r = app(context).providerRepositoryOrNull ?: throw RPCException(-32000, "provider repository unavailable")
        r.ensureConfigLoaded()
        return r
    }

    private fun categories(params: JSONObject, default: Set<BackupCategory>?): Set<BackupCategory>? {
        val arr = params.optJSONArray("categories") ?: return default
        return (0 until arr.length()).map { i ->
            val key = arr.getString(i)
            BackupCategory.entries.firstOrNull { it.key == key }
                ?: throw RPCException(-32602, "unknown category '$key' (known: ${BackupCategory.entries.joinToString { it.key }})")
        }.toSet()
    }

    private fun SubAgentDefinition.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("instructions", instructions)
        put("modelGroupId", modelGroupId ?: JSONObject.NULL)
        put("thinkingLevelOverride", thinkingLevelOverride?.name ?: JSONObject.NULL)
        put("isBuiltIn", isBuiltIn)
        put("sortOrder", sortOrder)
        put("updatedAt", updatedAt)
    }

    fun subAgentsList(context: Context): JSONObject =
        JSONObject().put("subAgents", JSONArray(repo(context).subAgents.map { it.toJson() }))

    fun subAgentsUpsert(context: Context, params: JSONObject): JSONObject {
        val r = repo(context)
        val name = params.optString("name").ifBlank { throw RPCException(-32602, "Missing 'name'") }
        val existing = params.optString("id").takeIf { it.isNotBlank() }?.let { id -> r.subAgents.firstOrNull { it.id == id } }
        val level = params.optString("thinkingLevelOverride").takeIf { it.isNotBlank() }
            ?.let { ThinkingLevel.parseOrNull(it) ?: throw RPCException(-32602, "bad thinkingLevelOverride '$it'") }
        val def = (existing ?: SubAgentDefinition(name = name, description = "")).copy(
            name = name,
            description = params.optString("description", existing?.description ?: ""),
            instructions = params.optString("instructions", existing?.instructions ?: ""),
            thinkingLevelOverride = level ?: existing?.thinkingLevelOverride,
        )
        r.upsertSubAgent(def)
        return JSONObject().put("id", def.id).put("subAgents", JSONArray(r.subAgents.map { it.toJson() }))
    }

    fun subAgentsDelete(context: Context, params: JSONObject): JSONObject {
        val r = repo(context)
        val id = params.optString("id").ifBlank { throw RPCException(-32602, "Missing 'id'") }
        r.deleteSubAgent(id)
        return JSONObject().put("deleted", id).put("remaining", r.subAgents.size)
    }

    /** Export into the app's files dir; no destination, no credentials by default. */
    suspend fun backupExport(context: Context, params: JSONObject): JSONObject {
        val a = app(context)
        val cats = categories(params, setOf(BackupCategory.PROVIDERS))!!
        val summary = BackupExporter(a, AppDatabase.getInstance(a)).export(
            BackupExporter.Options(
                categories = cats,
                includeCredentials = params.optBoolean("includeCredentials", false),
            ),
        )
        val outDir = File(a.filesDir, "debug-backup").apply { mkdirs() }
        val out = File(outDir, summary.packageFile.name)
        summary.packageFile.copyTo(out, overwrite = true)
        return JSONObject().apply {
            put("path", out.absolutePath)
            put("bytes", out.length())
            put("backupId", summary.backupId)
            put("members", JSONArray(BackupZip.listEntries(out)))
        }
    }

    /** Restore a package file already on the device. */
    suspend fun backupRestore(context: Context, params: JSONObject): JSONObject {
        val a = app(context)
        val pkg = File(params.optString("path").ifBlank { throw RPCException(-32602, "Missing 'path'") })
        if (!pkg.isFile) throw RPCException(-32602, "no package at ${pkg.absolutePath}")
        val work = File(a.cacheDir, "debug-restore-${System.currentTimeMillis()}")
        try {
            BackupZip.extract(pkg, work)
            val report = BackupImporter(a, AppDatabase.getInstance(a)).import(
                work,
                BackupImporter.Options(categories = categories(params, null)),
            )
            return JSONObject().apply {
                put("backupId", report.backupId)
                put("sourcePlatform", report.sourcePlatform ?: JSONObject.NULL)
                put("integrityChecked", report.integrityChecked)
                put("integrityFailed", JSONArray(report.integrityFailed))
                put("warnings", JSONArray(report.warnings))
                put("categories", JSONArray(report.categories.map { c ->
                    JSONObject().apply {
                        put("category", c.category)
                        put("imported", c.imported)
                        put("updated", c.updated)
                        put("skipped", c.skipped)
                        put("unreadable", c.unreadable)
                        put("failed", c.failed ?: JSONObject.NULL)
                    }
                }))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    // -- [T-android-backup-webdav-deadline] Upload drivers ------------------

    /**
     * Add a WebDAV destination for tests. Added DISABLED, so a real backup run
     * (which delivers to every enabled destination) never sends to it; only
     * [backupUpload], which names it explicitly, does.
     */
    fun remotesAddWebdav(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name").ifBlank { throw RPCException(-32602, "Missing 'name'") }
        val url = params.optString("url").ifBlank { throw RPCException(-32602, "Missing 'url'") }
        val store = com.openminis.app.backup.remote.RcloneRemoteStore(context)
        store.add(
            name = name, backend = "webdav",
            params = mapOf("url" to url, "vendor" to "other", "user" to params.optString("user", "test")),
            secret = params.optString("password", "test"), path = params.optString("path", ""),
        )
        store.setEnabled(name, false)
        return JSONObject().put("added", name).put("enabled", false)
    }

    fun remotesRemove(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name").ifBlank { throw RPCException(-32602, "Missing 'name'") }
        com.openminis.app.backup.remote.RcloneRemoteStore(context).remove(name)
        return JSONObject().put("removed", name)
    }

    /**
     * Upload a package to ONE named destination with the real uploader.
     * `cancelAfterMs` flips the cancel flag after that long, the way Stop
     * does during a backup run. Returns how long it took and how it ended.
     */
    fun backupUpload(context: Context, params: JSONObject): JSONObject {
        val file = File(params.optString("path").ifBlank { throw RPCException(-32602, "Missing 'path'") })
        if (!file.isFile) throw RPCException(-32602, "no package at ${file.absolutePath}")
        val store = com.openminis.app.backup.remote.RcloneRemoteStore(context)
        val remote = store.remote(params.optString("remote"))
            ?: throw RPCException(-32602, "no destination named '${params.optString("remote")}'")
        store.syncToRclone()
        val cancelAfter = params.optLong("cancelAfterMs", -1L)
        val started = System.currentTimeMillis()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = if (cancelAfter >= 0) {
            Thread {
                runCatching { Thread.sleep(cancelAfter) }
                cancelled.set(true)
            }.apply { isDaemon = true; start() }
        } else null
        val outcome = runCatching {
            com.openminis.app.backup.remote.RcloneChunkedUpload(context).upload(
                file, remote, "", isCancelled = { cancelled.get() },
            )
        }
        timer?.interrupt()
        val e = outcome.exceptionOrNull()
        return JSONObject().apply {
            put("ok", outcome.isSuccess)
            put("elapsedMs", System.currentTimeMillis() - started)
            put("cancelRequested", cancelled.get())
            put("error", e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: JSONObject.NULL)
        }
    }
}
