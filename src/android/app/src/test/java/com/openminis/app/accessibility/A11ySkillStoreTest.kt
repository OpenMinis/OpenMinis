package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A11ySkillStore 纯 JVM CRUD/LRU/stale/落盘容错。
 * 构造函数只吃 [File]（forContext 之外的纯构造），零 android 依赖
 * （落盘走 AtomicFileWrite + org.json，均在 test 依赖里）。
 */
class A11ySkillStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var storeSeq = 0
    private fun newStore(): A11ySkillStore = A11ySkillStore(tmp.newFolder("a11y-skills-${storeSeq++}"))

    private fun step(xy: String = "10,20", atMs: Long = 0L, text: String? = "OK") = A11ySkillStore.Step(
        kind = "TAP", packageName = "com.example", text = text,
        viewId = null, contentDescription = null, xy = xy, atMs = atMs,
    )

    // ─── CRUD ─────────────────────────────────────────────────────────────

    @Test
    fun `save then load round trips through the json file`() {
        val dir = tmp.newFolder("d1")
        val store = A11ySkillStore(dir)
        val saved = store.save("刷视频", "打开抖音刷视频", listOf(step()), "com.ss.android.ugc.aweme")
        assertNotNull(saved)
        // 新实例从磁盘读回 —— 证明 JSON 落盘而非仅内存缓存
        val reloaded = A11ySkillStore(dir)
        val s = reloaded.byName("刷视频")!!
        assertEquals("打开抖音刷视频", s.instruction)
        assertEquals("com.ss.android.ugc.aweme", s.packageName)
        assertEquals(1, s.steps.size)
        assertEquals("10,20", s.steps[0].xy)
        assertEquals(0, s.failStreak)
        assertFalse(s.stale)
    }

    @Test
    fun `save replaces by name keeping id and created at`() {
        val store = newStore()
        val first = store.save("a", "instruction a", listOf(step()), null)!!
        val second = store.save("A ", "instruction b", listOf(step(xy = "30,40")), null)!!
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals(1, store.all().size)
        assertEquals("instruction b", store.byName("a")!!.instruction)
        assertEquals("30,40", store.byId(first.id)!!.steps[0].xy)
    }

    @Test
    fun `save with blank name is rejected`() {
        assertNull(newStore().save("  ", "x", listOf(step()), null))
        assertNull(newStore().save("", "x", listOf(step()), null))
    }

    @Test
    fun `delete removes only the target id`() {
        val store = newStore()
        val a = store.save("a", "ia", listOf(step()), null)!!
        store.save("b", "ib", listOf(step()), null)
        assertTrue(store.delete(a.id))
        assertNull(store.byId(a.id))
        assertEquals(1, store.all().size)
        // 二次删除同一 id → false
        assertFalse(store.delete(a.id))
    }

    // ─── LRU 淘汰 ─────────────────────────────────────────────────────────

    @Test
    fun `lru eviction caps at 50 and drops the least recently used`() {
        val store = newStore()
        // 先灌 50 个，lastUsedAt 递增
        for (i in 0 until A11ySkillStore.MAX_SKILLS) {
            val s = store.save("k$i", "instruction $i", listOf(step()), null)!!
            store.touch(s.id, resetFailures = true)
        }
        assertEquals(A11ySkillStore.MAX_SKILLS, store.all().size)
        // 最老的 k0 被淘汰，新写入的 k50 存活
        store.save("k50", "instruction 50", listOf(step()), null)
        assertEquals(A11ySkillStore.MAX_SKILLS, store.all().size)
        assertNull(store.byName("k0"))
        assertNotNull(store.byName("k50"))
        // k1（次老）仍在
        assertNotNull(store.byName("k1"))
    }

    // ─── failStreak → stale ───────────────────────────────────────────────

    @Test
    fun `three consecutive failures auto stale and matching skips it`() {
        val store = newStore()
        val s = store.save("login", "打开抖音搜索华为手机", listOf(step()), null)!!
        assertTrue(store.recordFailure(s.id))
        assertEquals(1, store.byId(s.id)!!.failStreak)
        assertFalse(store.byId(s.id)!!.stale)
        store.recordFailure(s.id)
        store.recordFailure(s.id)
        val stale = store.byId(s.id)!!
        assertEquals(A11ySkillStore.STALE_AFTER_FAILURES, stale.failStreak)
        assertTrue(stale.stale)
        // stale 不参与匹配（在同名/同指令 1.0 的情况下也不匹配）
        assertNull(SkillMatcher.bestMatch("打开抖音搜索华为手机", store.all()))
    }

    @Test
    fun `touch with reset clears the failure streak`() {
        val store = newStore()
        val s = store.save("x", "ix", listOf(step()), null)!!
        store.recordFailure(s.id)
        store.recordFailure(s.id)
        assertEquals(2, store.byId(s.id)!!.failStreak)
        assertTrue(store.touch(s.id))
        assertEquals(0, store.byId(s.id)!!.failStreak)
        assertFalse(store.byId(s.id)!!.stale)
    }

    @Test
    fun `touch and recordFailure on unknown id return false`() {
        assertFalse(newStore().touch("ghost"))
        assertFalse(newStore().recordFailure("ghost"))
    }

    @Test
    fun `once stale stays stale even after touch`() {
        val store = newStore()
        val s = store.save("y", "iy", listOf(step()), null)!!
        repeat(A11ySkillStore.STALE_AFTER_FAILURES) { store.recordFailure(s.id) }
        store.touch(s.id) // 成功重放会清 failStreak
        val after = store.byId(s.id)!!
        assertEquals(0, after.failStreak)
        assertTrue("stale 标志必须单向锁存", after.stale)
    }

    // ─── updateResolvedXy（坐标自愈） ─────────────────────────────────────

    @Test
    fun `updateResolvedXy writes back per step coordinates`() {
        val store = newStore()
        val s = store.save(
            "flow", "flow",
            listOf(step(xy = "10,20"), step(xy = "30,40", atMs = 500L, text = null)), null,
        )!!
        assertTrue(store.updateResolvedXy(s.id, 1, 55, 66))
        val steps = store.byId(s.id)!!.steps
        assertNull(steps[0].resolvedXy)
        assertEquals("55,66", steps[1].resolvedXy)
        assertEquals("30,40", steps[1].xy) // 原始 xy 不动
    }

    @Test
    fun `updateResolvedXy rejects bad ids and out of range indexes`() {
        val store = newStore()
        val s = store.save("z", "iz", listOf(step()), null)!!
        assertFalse(store.updateResolvedXy("ghost", 0, 1, 2))
        assertFalse(store.updateResolvedXy(s.id, 1, 1, 2)) // 只有 1 步
        assertFalse(store.updateResolvedXy(s.id, -1, 1, 2))
    }

    // ─── 坏文件容错 ───────────────────────────────────────────────────────

    @Test
    fun `corrupt json file loads as empty store without crashing`() {
        val dir = tmp.newFolder("d2")
        File(dir, A11ySkillStore.FILE_NAME).writeText("{not json!!")
        val store = A11ySkillStore(dir)
        assertEquals(0, store.all().size)
        // 且能继续写入
        assertNotNull(store.save("recovered", "ir", listOf(step()), null))
    }

    @Test
    fun `missing or empty directory behaves as empty store`() {
        val store = A11ySkillStore(File(tmp.root, "never-created"))
        assertEquals(0, store.all().size)
        // 首次 save 自动 mkdirs + 落盘
        assertNotNull(store.save("first", "if", listOf(step()), null))
        assertEquals(1, A11ySkillStore(File(tmp.root, "never-created")).all().size)
    }

    @Test
    fun `json array instead of skills object tolerates gracefully`() {
        val dir = tmp.newFolder("d3")
        File(dir, A11ySkillStore.FILE_NAME).writeText("""["not","an","object"]""")
        assertEquals(0, A11ySkillStore(dir).all().size)
        // skills 字段缺失
        val dir2 = tmp.newFolder("d4")
        File(dir2, A11ySkillStore.FILE_NAME).writeText("""{"version":1}""")
        assertEquals(0, A11ySkillStore(dir2).all().size)
    }

    // ─── forDir 进程级单例（并发安全的前提） ─────────────────────────────

    @Test
    fun `forDir returns the same instance for the same dir`() {
        A11ySkillStore.resetSingletonForTest()
        try {
            val dir = tmp.newFolder("singleton-a")
            val first = A11ySkillStore.forDir(dir)
            val second = A11ySkillStore.forDir(dir)
            assertTrue("同目录必须复用同一实例（否则 @Synchronized 锁跨实例无效）", first === second)
        } finally {
            A11ySkillStore.resetSingletonForTest()
        }
    }

    @Test
    fun `forDir swaps the cached instance when the dir changes and reset clears it`() {
        A11ySkillStore.resetSingletonForTest()
        try {
            val dirA = tmp.newFolder("singleton-b")
            val dirB = tmp.newFolder("singleton-c")
            val a = A11ySkillStore.forDir(dirA)
            val b = A11ySkillStore.forDir(dirB)
            assertTrue(a !== b)
            // 换回 a 的目录 → 重新创建（单槽缓存，生产只有一个 dir）
            val aAgain = A11ySkillStore.forDir(dirA)
            assertTrue(a !== aAgain)
            A11ySkillStore.resetSingletonForTest()
            val afterReset = A11ySkillStore.forDir(dirA)
            assertTrue(a !== afterReset)
        } finally {
            A11ySkillStore.resetSingletonForTest()
        }
    }

    @Test
    fun `two separate instances over one dir lose updates - the bug the singleton fixes`() {
        // 旧 forContext 每次调用 new 一个实例的形态：两个实例各自持有
        // 独立 cache + 独立锁，读-改-写序列互相覆盖（last-writer-wins）。
        val dir = tmp.newFolder("lost-update")
        val a = A11ySkillStore(dir)
        val b = A11ySkillStore(dir)
        b.all() // b 先加载（空 cache）
        a.save("a", "ia", listOf(step()), null)
        b.save("b", "ib", listOf(step()), null) // b 用自己的旧 cache 整体重写文件
        val disk = A11ySkillStore(dir)
        // b 的整文件重写把 a 的写入吞掉了 —— 这就是丢更新
        assertNull("双实例互写必然丢更新（记录旧缺陷形态）", disk.byName("a"))
        assertNotNull(disk.byName("b"))
    }

    @Test
    fun `concurrent saves on the shared singleton instance lose nothing`() {
        A11ySkillStore.resetSingletonForTest()
        try {
            val dir = tmp.newFolder("concurrent")
            val store = A11ySkillStore.forDir(dir)
            val threads = (0 until 8).map { t ->
                Thread {
                    repeat(5) { i -> store.save("skill-$t-$i", "i-$t-$i", listOf(step()), null) }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertEquals(40, store.all().size)
            assertEquals(40, A11ySkillStore(dir).all().size)
        } finally {
            A11ySkillStore.resetSingletonForTest()
        }
    }

    // ─── fromRecorderSteps ────────────────────────────────────────────────

    @Test
    fun `fromRecorderSteps maps recorder enums to string kinds`() {
        val recorded = listOf(
            A11yScriptRecorder.Step(
                kind = A11yScriptRecorder.Step.Kind.TAP,
                packageName = "com.example",
                text = "OK",
                viewId = null,
                contentDescription = null,
                xy = "10,20",
                atMs = 5L,
            ),
            A11yScriptRecorder.Step(
                kind = A11yScriptRecorder.Step.Kind.INPUT,
                packageName = "com.example",
                text = "hello",
                viewId = null,
                contentDescription = null,
                xy = null,
                atMs = 900L,
            ),
        )
        val mapped = A11ySkillStore.fromRecorderSteps(recorded)
        assertEquals(2, mapped.size)
        assertEquals("TAP", mapped[0].kind)
        assertEquals("INPUT", mapped[1].kind)
        assertEquals(900L, mapped[1].atMs)
        assertNull(mapped[1].xy)
        assertNull(mapped[0].resolvedXy) // 新录制的没有自愈坐标
    }
}
