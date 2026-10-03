package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-subagent-strings] Port of iOS 348f1b528: the Sub Agents settings
 * page rendered several languages at once (a Spanish UI showed English
 * "Sub Agents" / "Add Sub Agent" and footers next to translated "Integrado").
 *
 * The page's strings must exist in every locale iOS translates them into
 * (zh-Hans, zh-Hant, ja, ko, de, es, fr); Croatian is complete too. Other
 * locales deliberately fall back to English rather than a machine
 * translation.
 */
class SubAgentsStringsCoverageTest {

    private val res = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")

    private fun strings(dir: String): Map<String, String> {
        val xml = java.io.File(res, "$dir/strings.xml").readText()
        return Regex("""<string name="(sub_agents?_[^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val base = strings("values")

    @Test
    fun `every Sub Agents string is translated in the covered locales`() {
        assertTrue("the page has its strings", base.size >= 27)
        for (dir in listOf("values-zh", "values-zh-rTW", "values-ja", "values-ko", "values-de", "values-es", "values-fr", "values-hr")) {
            val missing = base.keys - strings(dir).keys
            assertEquals("$dir is missing $missing", emptySet<String>(), missing)
        }
    }

    @Test
    fun `format arguments survive translation`() {
        for (dir in listOf("values-zh", "values-zh-rTW", "values-ja", "values-ko", "values-de", "values-es", "values-fr", "values-hr")) {
            val s = strings(dir)
            for ((key, en) in base) {
                val args = Regex("""%\d\$[sd]""").findAll(en).map { it.value }.toSet()
                if (args.isEmpty()) continue
                val translated = s[key] ?: continue
                for (a in args) assertTrue("$dir/$key lost $a", translated.contains(a))
            }
        }
    }

    @Test
    fun `apostrophes are escaped so aapt accepts them`() {
        for (dir in listOf("values-zh-rTW", "values-ja", "values-ko", "values-de", "values-es", "values-fr")) {
            for ((key, v) in strings(dir)) {
                assertTrue("$dir/$key has an unescaped apostrophe", !Regex("""(?<!\\)'""").containsMatchIn(v))
            }
        }
    }
}
