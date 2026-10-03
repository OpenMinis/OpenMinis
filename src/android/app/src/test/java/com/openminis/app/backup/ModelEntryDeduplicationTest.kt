package com.openminis.app.backup

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelEntryDeduplicationTest {

    private fun makeEntry(
        providerInstanceId: String,
        modelId: String,
        displayName: String,
        uuid: String = java.util.UUID.randomUUID().toString(),
    ) = ModelEntry(
        providerInstanceId = providerInstanceId,
        baseModel = LLMModel(id = modelId, displayName = displayName, provider = "p"),
        uuid = uuid,
    )

    private fun mergeEntries(local: List<ModelEntry>, remote: List<ModelEntry>): List<ModelEntry> {
        val mergedEntries = local.toMutableList()
        val entryKeys = mergedEntries.map { it.providerInstanceId to it.baseModel.id }.toMutableSet()
        for (entry in remote) {
            val key = entry.providerInstanceId to entry.baseModel.id
            if (key !in entryKeys) {
                mergedEntries.add(entry); entryKeys.add(key)
            } else {
                val localIdx = mergedEntries.indexOfFirst {
                    it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
                }
                if (localIdx >= 0) {
                    val loc = mergedEntries[localIdx]
                    val localDN = loc.baseModel.displayName; val remoteDN = entry.baseModel.displayName
                    if ((localDN.isBlank() || localDN == loc.baseModel.id) &&
                        remoteDN.isNotBlank() && remoteDN != entry.baseModel.id) {
                        mergedEntries[localIdx] = loc.copy(baseModel = loc.baseModel.copy(displayName = remoteDN))
                    }
                }
            }
        }
        return mergedEntries
    }

    @Test fun `same provider and model id in remote does not produce duplicate`() {
        val local = listOf(makeEntry("prov-1", "deepseek-v4-pro", "deepseek-v4-pro", uuid = "uuid-local"))
        val remote = listOf(makeEntry("prov-1", "deepseek-v4-pro", "DeepSeek V4 Pro", uuid = "uuid-remote"))
        assertEquals(1, mergeEntries(local, remote).size)
    }

    @Test fun `local degraded displayName is updated from remote good name`() {
        val local = listOf(makeEntry("prov-1", "deepseek-v4-pro", "deepseek-v4-pro"))
        val remote = listOf(makeEntry("prov-1", "deepseek-v4-pro", "DeepSeek V4 Pro"))
        assertEquals("DeepSeek V4 Pro", mergeEntries(local, remote).single().baseModel.displayName)
    }

    @Test fun `local good displayName is not overwritten by remote degraded name`() {
        val local = listOf(makeEntry("prov-1", "deepseek-v4-pro", "DeepSeek V4 Pro"))
        val remote = listOf(makeEntry("prov-1", "deepseek-v4-pro", "deepseek-v4-pro"))
        assertEquals("DeepSeek V4 Pro", mergeEntries(local, remote).single().baseModel.displayName)
    }

    @Test fun `different model id in remote is added without dedup`() {
        val local = listOf(makeEntry("prov-1", "model-a", "Model A"))
        val remote = listOf(makeEntry("prov-1", "model-b", "Model B"))
        assertEquals(2, mergeEntries(local, remote).size)
    }

    private fun resolveDisplayName(model: LLMModel, prior: ModelEntry?): String = when {
        model.displayName.isBlank() || model.displayName == model.id ->
            prior?.baseModel?.displayName?.takeIf { it.isNotBlank() && it != model.id } ?: model.displayName
        else -> model.displayName
    }

    @Test fun `remote bare model-id displayName keeps prior good name`() {
        val prior = makeEntry("prov-1", "deepseek-v4-pro", "DeepSeek V4 Pro")
        assertEquals("DeepSeek V4 Pro", resolveDisplayName(LLMModel("deepseek-v4-pro", "deepseek-v4-pro", "p"), prior))
    }

    @Test fun `remote meaningful displayName overrides prior`() {
        val prior = makeEntry("prov-1", "deepseek-v4-pro", "Old Name")
        assertEquals("DeepSeek V4 Pro (2025)", resolveDisplayName(LLMModel("deepseek-v4-pro", "DeepSeek V4 Pro (2025)", "p"), prior))
    }

    @Test fun `no prior entry uses remote displayName directly`() {
        assertEquals("deepseek-v4-pro", resolveDisplayName(LLMModel("deepseek-v4-pro", "deepseek-v4-pro", "p"), null))
    }

    @Test fun `no prior entry with good remote name uses remote`() {
        assertEquals("DeepSeek V4 Pro", resolveDisplayName(LLMModel("deepseek-v4-pro", "DeepSeek V4 Pro", "p"), null))
    }
}
