package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionLogicTest {

    // The verbatim CI body of the android-latest rolling release fetched
    // 2026-10-05 (versionName 2.0.39 / versionCode 239). Note the full-width
    // colons — the ASCII-only parsers returned null against this exact text,
    // which pushed every upgrade decision onto the asset-timestamp heuristic.
    private val LIVE_ROLLING_BODY = """
        来自 `6b9ebdcf06c2ec53836e643fc6f3e6a7207d886b` 的滚动 Android 构建。正式版请看带版本号的 Release。

        - 包名：`com.openminis.linux`
        - 启动器名称：**Minis Ultra**
        - 架构：arm64-v8a
        - NDK：r29 `29.0.14206865`（与应用内安装同一修订号，不回退 r28）
        - versionName：`2.0.39`
        - versionCode：239
        - 签名说明见 BUILDING.md
    """.trimIndent()

    private fun rolling(
        code: Int? = 29,
        name: String? = "1.17-linux",
        updatedAt: Long = 2_000_000L,
        apk: String? = "https://example.com/app.apk",
    ) = UpdateVersionLogic.ReleaseCandidate(
        tagName = "android-latest",
        versionName = "android",
        releaseName = "Minis Ultra (Android)",
        changelog = "versionName: `$name`\nversionCode: $code",
        apkUrl = apk,
        apkSize = 1L,
        apkUpdatedAtMs = updatedAt,
        bodyVersionCode = code,
        bodyVersionName = name,
    )

    private fun tagged(
        tag: String,
        apk: String? = "https://example.com/app.apk",
    ) = UpdateVersionLogic.ReleaseCandidate(
        tagName = tag,
        versionName = UpdateVersionLogic.normalizeTag(tag),
        releaseName = tag,
        changelog = "",
        apkUrl = apk,
        apkSize = 1L,
        apkUpdatedAtMs = 0L,
        bodyVersionCode = null,
        bodyVersionName = null,
    )

    @Test
    fun `normalizeTag strips linux suffix`() {
        assertEquals("1.16", UpdateVersionLogic.normalizeTag("1.16-linux"))
        assertEquals("1.16", UpdateVersionLogic.normalizeTag("v1.16-linux"))
    }

    @Test
    fun `android-latest is not a semver upgrade by tag alone`() {
        val c = rolling(code = 28, name = "1.16-linux", updatedAt = 1000L)
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                c,
                localVer = "1.16",
                localCode = 28,
                localLastUpdateMs = 5000L,
            ),
        )
    }

    @Test
    fun `rolling versionCode bump is an upgrade`() {
        val c = rolling(code = 29, name = "1.17-linux")
        assertTrue(
            UpdateVersionLogic.isNewerThanLocal(
                c, "1.16", 28, localLastUpdateMs = 9_000_000L,
            ),
        )
        val picked = UpdateVersionLogic.pickUpgrade(listOf(c), "1.16", 28, 9_000_000L)
        assertNotNull(picked)
        assertEquals("1.17-linux", UpdateVersionLogic.displayVersion(picked!!))
    }

    @Test
    fun `rolling rebuilt APK with same versionCode is not an upgrade`() {
        val c = rolling(code = 28, name = "1.16-linux", updatedAt = 10_000_000L)
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                c, "1.16", 28, localLastUpdateMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun `rolling without bodyCode needs newer versionName not just timestamp`() {
        val same = rolling(code = null, name = "1.16-linux", updatedAt = 10_000_000L)
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(same, "1.16", 28, localLastUpdateMs = 1_000_000L),
        )
        val newerName = rolling(code = null, name = "1.17-linux", updatedAt = 10_000_000L)
        assertTrue(
            UpdateVersionLogic.isNewerThanLocal(newerName, "1.16", 28, localLastUpdateMs = 1_000_000L),
        )
    }

    @Test
    fun `pickUpgrade prefers semver name over rolling bodyCode`() {
        val rollingLow = rolling(code = 20, name = "1.19-linux")
        val semver = tagged("2.0-linux")
        val picked = UpdateVersionLogic.pickUpgrade(
            listOf(rollingLow, semver),
            "1.0",
            1,
            0L,
        )
        assertEquals("2.0-linux", picked?.tagName)
    }

    @Test
    fun `semver tag still wins over local`() {
        val c = tagged("1.17-linux")
        assertTrue(UpdateVersionLogic.isNewerThanLocal(c, "1.16", 28, 0L))
        assertFalse(UpdateVersionLogic.isNewerThanLocal(tagged("1.16-linux"), "1.16", 28, 0L))
    }

    @Test
    fun `rolling tag is excluded from highest semver`() {
        val highest = UpdateVersionLogic.highestPublished(
            listOf(rolling(), tagged("1.16-linux")),
        )
        assertEquals("1.16-linux", highest?.tagName)
    }

    @Test
    fun `body parsers`() {
        val body = """
            Rolling Android build.

            - versionName: `1.17-linux`
            - versionCode: 29
        """.trimIndent()
        assertEquals(29, UpdateVersionLogic.parseVersionCodeFromBody(body))
        assertEquals("1.17-linux", UpdateVersionLogic.parseVersionNameFromBody(body))
    }

    @Test
    fun `no apk means not newer`() {
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                rolling(apk = null), "1.16", 28, 0L,
            ),
        )
        assertNull(
            UpdateVersionLogic.pickUpgrade(listOf(rolling(apk = null)), "1.16", 28, 0L),
        )
    }

    @Test
    fun `stripReleaseMetadata drops version lines`() {
        val raw = "Rolling Android build.\n\n- versionName: `1.17-linux`\n- versionCode: 29"
        assertEquals("Rolling Android build.", UpdateVersionLogic.stripReleaseMetadata(raw))
    }

    @Test
    fun `live rolling body parses across the full-width colon`() {
        assertEquals(239, UpdateVersionLogic.parseVersionCodeFromBody(LIVE_ROLLING_BODY))
        assertEquals("2.0.39", UpdateVersionLogic.parseVersionNameFromBody(LIVE_ROLLING_BODY))
    }

    @Test
    fun `stripReleaseMetadata drops full-width colon metadata lines`() {
        val stripped = UpdateVersionLogic.stripReleaseMetadata(LIVE_ROLLING_BODY)
        assertTrue(stripped.contains("滚动 Android 构建"))
        assertFalse(stripped.contains("versionCode"))
        assertFalse(stripped.contains("versionName"))
    }

    @Test
    fun `live body with same versionCode is not an upgrade`() {
        // Regression for the 2026-10-05 false positive: the full-width colon
        // made the body parsers return null, the "android" apkName beat
        // "2.0.39" lexicographically, and the newer asset timestamp claimed
        // an update for users already on the newest build. Candidate fields
        // are built through the same parsers UpdateChecker uses, so this
        // exercises the whole pipeline instead of hand-seeding bodyCode.
        val c = rolling(updatedAt = 10_000_000L).copy(
            bodyVersionCode = UpdateVersionLogic.parseVersionCodeFromBody(LIVE_ROLLING_BODY),
            bodyVersionName = UpdateVersionLogic.parseVersionNameFromBody(LIVE_ROLLING_BODY),
        )
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                c, "2.0.39", 239, localLastUpdateMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun `unreadable rolling body fails closed even with newer asset`() {
        val c = rolling(code = null, name = null, updatedAt = 10_000_000L)
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                c, "2.0.39", 239, localLastUpdateMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun `highestPublished compares pairwise not against zero`() {
        assertEquals(
            "1.0.1-linux",
            UpdateVersionLogic.highestPublished(listOf(tagged("1.0-linux"), tagged("1.0.1-linux")))?.tagName,
        )
        assertEquals(
            "1.0.1-linux",
            UpdateVersionLogic.highestPublished(listOf(tagged("1.0.1-linux"), tagged("1.0-linux")))?.tagName,
        )
    }

    @Test
    fun `resolveChangelog prefers tagged notes for rolling even when boilerplate is long`() {
        val notes = "## Changes\n- real release notes for the same version"
        val rollingRel = rolling(code = 239, name = "2.0.39").copy(changelog = LIVE_ROLLING_BODY)
        val tag = tagged("2.0.39-linux").copy(changelog = notes)
        val resolved = UpdateVersionLogic.resolveChangelog(rollingRel, listOf(rollingRel, tag))
        assertTrue(resolved.contains("real release notes"))
        assertFalse(resolved.contains("滚动 Android 构建"))
    }

    @Test
    fun `resolveChangelog uses tagged notes when rolling body is metadata`() {
        val notes = "## Changes\n- chat video generation in-session\n- Videos API fallbacks"
        val rollingRel = rolling(code = 57, name = "1.36.4-linux")
        val tag = tagged("1.36.4-linux").copy(changelog = notes)
        val resolved = UpdateVersionLogic.resolveChangelog(rollingRel, listOf(rollingRel, tag))
        assertTrue(resolved.contains("chat video generation"))
        assertFalse(resolved.contains("versionCode"))
    }

    @Test
    fun `resolveChangelog keeps long notes on the chosen release`() {
        val notes = "# Minis Ultra 1.36.4\n\n" + ("x".repeat(80))
        val c = tagged("1.36.4-linux").copy(changelog = notes)
        assertEquals(notes, UpdateVersionLogic.resolveChangelog(c, listOf(c)))
    }
}
