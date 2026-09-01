package com.openminis.app.backup

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-envvar-iso8601-wire] Cross-platform wire format of
 * `data/env_vars.json`'s `createdAt`.
 *
 * A real-device Android→iOS restore failed only on Environment Variables:
 * Android wrote `createdAt` as a bare epoch-millis number, but iOS
 * `EnvVarEntry` decodes `createdAt: Date` with `.iso8601`, so it rejected the
 * number with "isn't in the correct format" and rolled the category back.
 *
 * The fix makes Android WRITE an ISO-8601 string (what iOS expects) while still
 * READING both the new ISO strings and legacy Android numeric epoch millis.
 * These are pure-JVM serialization assertions — the exact bytes on the wire and
 * the round-trip — which is the invariant the cross-platform restore depends on.
 */
class BackupEnvVarWireFormatTest {

    private val listSerializer = ListSerializer(BackupEnvVarMeta.serializer())

    /** New Android exports write createdAt as an ISO-8601 STRING, not a number. */
    @Test
    fun `createdAt is written as an ISO-8601 string`() {
        // 2026-08-21T00:00:00Z = 1787270400000 ms.
        val millis = 1787270400000L
        val json = BackupFormat.json.encodeToString(
            listSerializer,
            listOf(BackupEnvVarMeta(id = "e1", key = "FOO", note = "n", createdAt = millis)),
        )
        // The value must be a quoted ISO string, never a bare number.
        assertTrue(
            "createdAt should be a quoted ISO-8601 string, was: $json",
            json.contains("\"createdAt\":\"2026-08-21T00:00:00Z\""),
        )
        assertTrue(
            "createdAt must not be a bare number, was: $json",
            !json.contains("\"createdAt\":1787270400000"),
        )
    }

    /** Android importer reads ISO-8601 strings (the shape iOS writes). */
    @Test
    fun `importer reads an ISO-8601 string createdAt`() {
        val json = """[{"id":"e1","key":"FOO","note":"","createdAt":"2026-08-21T00:00:00Z"}]"""
        val metas = BackupFormat.json.decodeFromString(listSerializer, json)
        assertEquals(1, metas.size)
        assertEquals("FOO", metas[0].key)
        assertEquals(1787270400000L, metas[0].createdAt)
    }

    /** Legacy Android packages wrote a bare number; those must still decode. */
    @Test
    fun `importer still reads legacy numeric epoch-millis createdAt`() {
        val json = """[{"id":"e1","key":"BAR","note":"","createdAt":1787270400000}]"""
        val metas = BackupFormat.json.decodeFromString(listSerializer, json)
        assertEquals(1, metas.size)
        assertEquals("BAR", metas[0].key)
        assertEquals(1787270400000L, metas[0].createdAt)
    }

    /** Missing createdAt (tolerant default) doesn't throw. */
    @Test
    fun `missing createdAt defaults to zero`() {
        val json = """[{"id":"e1","key":"BAZ"}]"""
        val metas = BackupFormat.json.decodeFromString(listSerializer, json)
        assertEquals(0L, metas[0].createdAt)
    }

    /**
     * [T-android-provider-iso8601-wire] `provider_config.json` has the same
     * contract: iOS `ProviderInstance.createdAt` is a `Date` decoded with
     * `.iso8601`, so a bare epoch number made iOS's importProviders fail to
     * decode the whole file and report `Unreadable: 1`.
     */
    @Test
    fun `provider instance createdAt is written as ISO and reads both forms`() {
        val millis = 1787270400000L
        val inst = com.openminis.app.data.model.ProviderInstance(
            id = "i1",
            label = "L",
            providerType = com.openminis.app.data.model.ProviderType.openAI,
            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
            createdAt = millis,
        )
        val json = BackupFormat.json.encodeToString(
            com.openminis.app.data.model.ProviderInstance.serializer(), inst,
        )
        assertTrue(
            "provider createdAt should be an ISO string, was: $json",
            json.contains("\"createdAt\":\"2026-08-21T00:00:00Z\""),
        )
        // ISO decodes back
        val back = BackupFormat.json.decodeFromString(
            com.openminis.app.data.model.ProviderInstance.serializer(), json,
        )
        assertEquals(millis, back.createdAt)
        // Legacy numeric still decodes (existing local mirror + old backups)
        val legacy = """{"id":"i1","label":"L","providerType":"openAI",""" +
            """"credentialType":"apiKey","createdAt":1787270400000}"""
        val old = BackupFormat.json.decodeFromString(
            com.openminis.app.data.model.ProviderInstance.serializer(), legacy,
        )
        assertEquals(millis, old.createdAt)
    }

    /** Nullable peer: userModifiedAt round-trips, and null stays null. */
    @Test
    fun `model entry userModifiedAt is ISO, nullable, and reads legacy numbers`() {
        val millis = 1787270400000L
        val ser = Iso8601MillisNullableSerializer
        assertEquals("\"2026-08-21T00:00:00Z\"", BackupFormat.json.encodeToString(ser, millis))
        assertEquals("null", BackupFormat.json.encodeToString(ser, null))
        assertEquals(millis, BackupFormat.json.decodeFromString(ser, "\"2026-08-21T00:00:00Z\""))
        assertEquals(millis, BackupFormat.json.decodeFromString(ser, "1787270400000"))
        assertEquals(null, BackupFormat.json.decodeFromString(ser, "null"))
    }

    /** Full round-trip: encode then decode is stable at second precision. */
    @Test
    fun `round-trip preserves createdAt at second precision`() {
        val millis = 1787270400000L
        val encoded = BackupFormat.json.encodeToString(
            listSerializer, listOf(BackupEnvVarMeta(id = "e1", key = "K", createdAt = millis)),
        )
        val decoded = BackupFormat.json.decodeFromString(listSerializer, encoded)
        assertEquals(millis, decoded[0].createdAt)
    }
}
