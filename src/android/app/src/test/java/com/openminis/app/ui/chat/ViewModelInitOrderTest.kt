package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-recentlyfailed-init-order] Every property `loadSession()` reads
 * synchronously must be declared ABOVE `init { … }`.
 *
 * Kotlin initialises a class body top to bottom, and `init { loadSession() }`
 * launches on `Dispatchers.Main.immediate`, which runs synchronously up to the
 * first suspension point. That stretch reaches `resolveProviderFromGroup`. A
 * property declared BELOW `init` has not run its initialiser by then, so the
 * read yields the JVM default — `null` for a reference type — and
 * `it.id !in recentlyFailedEntryIds` throws
 *
 *   NullPointerException: Attempt to invoke interface method
 *   'boolean java.util.Set.contains(java.lang.Object)' on a null object reference
 *
 * inside the constructor. The ViewModel never finishes building, so the crash
 * repeats on every launch that opens a session bound to a model group — which
 * is what made it a crash LOOP rather than a one-off: nothing bad is persisted,
 * the session simply keeps reopening and keeps re-entering the same
 * construction path. Shipped in 1.14(26); six field reports on 2026-09-13
 * (Xiaomi 23046RP50C / Android 15, HUAWEI MNA-AL00 / Android 12), all with
 * byte-identical top frames.
 *
 * The defect is invisible in review — the declaration reads perfectly fine
 * where it is, and the compiler is silent because the type is non-null on
 * paper. Only the ORDER is wrong. So the guard is a source scan: it is the one
 * check that can see what a runtime test of a correctly-ordered file cannot.
 */
class ViewModelInitOrderTest {

    private fun source(): List<String> {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("ChatViewModel.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        return f.readLines()
    }

    /** Line number (1-based) of the class-body `init {`, which runs loadSession(). */
    private fun initLine(lines: List<String>): Int {
        val idx = lines.indexOfFirst { it.trimEnd() == "    init {" }
        assertTrue("no class-body `init {` found in ChatViewModel", idx >= 0)
        return idx + 1
    }

    private fun declarationLine(lines: List<String>, name: String): Int {
        val idx = lines.indexOfFirst {
            Regex("""^\s*(?:private |internal )?(?:val|var)\s+$name\b""").containsMatchIn(it)
        }
        assertTrue("property `$name` not found in ChatViewModel", idx >= 0)
        return idx + 1
    }

    @Test
    fun `recentlyFailedEntryIds is declared before init`() {
        // The exact field that crashed: read by resolveProviderFromGroup via
        // `it.id !in recentlyFailedEntryIds`, on the synchronous stretch of
        // loadSession().
        val lines = source()
        val decl = declarationLine(lines, "recentlyFailedEntryIds")
        val init = initLine(lines)
        assertTrue(
            "recentlyFailedEntryIds is declared at line $decl, BELOW `init` at line $init — " +
                "loadSession() reads it synchronously, so it will be null and the " +
                "ViewModel constructor will throw NPE on every launch",
            decl < init,
        )
    }

    @Test
    fun `every property the synchronous load path reads is declared before init`() {
        // Generalised: the same trap applies to anything else loadSession() and
        // the functions it calls before suspending happen to touch. Keeping
        // this list derived from the source rather than hard-coded means a new
        // field added to that path is covered without anyone remembering to.
        val lines = source()
        val init = initLine(lines)
        val text = lines.joinToString("\n")

        val scope = buildString {
            for (fn in listOf("loadSession", "resolveProviderFromGroup", "applyGroupSessionDefaults")) {
                val m = Regex("""\n\s*(?:private |internal )?(?:suspend )?fun $fn\(""").find(text)
                if (m != null) {
                    var depth = 0
                    var i = text.indexOf('{', m.range.first)
                    val start = i
                    while (i < text.length) {
                        if (text[i] == '{') depth++
                        else if (text[i] == '}') { depth--; if (depth == 0) break }
                        i++
                    }
                    append(text.substring(start, minOf(i + 1, text.length)))
                }
            }
        }
        assertTrue("could not extract the load path from the source", scope.length > 200)

        // Class-body properties declared after `init` with a plain initialiser.
        // `by lazy` is exempt: it resolves on first read, not in declaration
        // order, which is exactly the hazard being tested for.
        val offenders = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val lineNo = i + 1
            if (lineNo <= init) continue
            val m = Regex("""^    (?:private |internal )?(?:val|var) (\w+)\s*(?::[^=]+)?=""")
                .find(line) ?: continue
            if ("by lazy" in line) continue
            val name = m.groupValues[1]
            if (Regex("""\b$name\b""").containsMatchIn(scope)) {
                offenders.add("$name (line $lineNo)")
            }
        }

        assertTrue(
            "these properties are declared BELOW `init` (line $init) yet are read on " +
                "loadSession()'s synchronous path — each will be null/default when the " +
                "constructor runs: $offenders",
            offenders.isEmpty(),
        )
    }
}
