package com.openminis.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigWorkingCopyTest {

    // [T-android-subagents-working-copy] Every MutableList on ProviderConfig is
    // edited in place by some mutator; workingCopy must copy each one or the
    // edit lands on the published config under lock-free readers.
    @Test
    fun `workingCopy copies every mutable list of ProviderConfig`() {
        val cfg = com.openminis.app.ProductionSources.read("data/model/ProviderConfig.kt")
        val body = cfg.substringAfter("data class ProviderConfig(").substringBefore("\n)")
        val lists = Regex("""val (\w+): MutableList<""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("found the lists: $lists", "subAgents" in lists && "instances" in lists)
        val repo = com.openminis.app.ProductionSources.read("data/repository/ProviderRepository.kt")
        val wc = repo.substringAfter("private fun workingCopy(): ProviderConfig {").substringBefore("\n    }")
        for (name in lists) assertTrue("workingCopy must copy $name", wc.contains("$name = live.$name"))
    }
}
