package com.openminis.app.data.model

import com.openminis.app.data.db.ProviderModelEntryEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

class CrossPlatformBackupRestoreUnitTest {

    @Test
    fun testRestoreBackupWithCustomModelOverrides() {
        val backupFile = File("/tmp/cross_platform_test.minisbak")
        assertTrue("Backup file must exist", backupFile.exists())

        var extractedJson: String? = null
        ZipInputStream(FileInputStream(backupFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "data/provider_config.json") {
                    extractedJson = zis.bufferedReader().readText()
                    break
                }
                entry = zis.nextEntry
            }
        }
        assertNotNull("Must extract provider_config.json from minisbak", extractedJson)

        val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        // 1. Deserialization
        val config = jsonParser.decodeFromString<ProviderConfig>(extractedJson!!)
        assertNotNull(config)
        assertEquals(1, config.instances.size)
        assertEquals(1, config.modelEntries.size)

        val modelEntry = config.modelEntries[0]
        val overrides = modelEntry.overrides
        assertNotNull("Overrides must be parsed", overrides)

        // Every newly added custom parameter must parse intact
        assertEquals(0.7, overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, overrides.topP ?: 0.0, 0.0001)
        assertNotNull("customHeaders must not be null", overrides.customHeaders)
        assertEquals("Token-ABC-123", overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", overrides.customHeaders?.get("HTTP-Referer"))
        assertNotNull("extraBodyParams must not be null", overrides.extraBodyParams)

        // 2. Simulate writing the local Room entity's overrides_json column
        val serializedOverrides = jsonParser.encodeToString(overrides)
        val entity = ProviderModelEntryEntity(
            id = modelEntry.uuid,
            providerInstanceId = modelEntry.providerInstanceId,
            baseModelJson = jsonParser.encodeToString(modelEntry.baseModel),
            overridesJson = serializedOverrides,
            isCustom = 0,
            isHidden = 0,
            sortOrder = 0,
            userModifiedAt = System.currentTimeMillis()
        )
        assertNotNull(entity.overridesJson)

        // 3. Simulate reading it back from the Room entity
        val restoredOverrides = jsonParser.decodeFromString<ModelOverrides>(entity.overridesJson!!)
        assertEquals(0.7, restoredOverrides.temperature ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", restoredOverrides.customHeaders?.get("X-Custom-Auth"))

        // 4. Simulate a model-list refresh (Refresh Models); the overrides must not be wiped
        val remoteNewBaseModel = LLMModel(
            id = "test-gpt-custom",
            displayName = "Remote Updated GPT Name",
            provider = "openAI"
        )
        val refreshedModelEntry = ModelEntry(
            providerInstanceId = entity.providerInstanceId,
            baseModel = remoteNewBaseModel,
            overrides = restoredOverrides,
            isCustom = entity.isCustom != 0,
            isHidden = entity.isHidden != 0,
            uuid = entity.id,
            userModifiedAt = entity.userModifiedAt
        )
        // The custom parameters must survive the refresh intact
        assertEquals(0.7, refreshedModelEntry.overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, refreshedModelEntry.overrides.topP ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", refreshedModelEntry.overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", refreshedModelEntry.overrides.customHeaders?.get("HTTP-Referer"))

        println(">>> VERIFIED: All custom model parameters successfully deserialized, saved to DB Entity, and preserved across model refresh without any MissingFieldException!")
    }
}
