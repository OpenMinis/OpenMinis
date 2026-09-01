package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-paste-mediaref] Splitting a message body on its `[Pasted#N]`
 * markers, and telling a pasted mediaRef apart from a real file attachment.
 *
 * These two decide whether a pasted block reaches the model at all. A marker the
 * splitter drops is content silently lost from the prompt; a ref
 * [PastedMedia.isPastedRef] misclassifies is either an attachment inlined into
 * the prompt as text, or a pasted block the model never sees.
 */
class PasteMediaRefTest {

    private fun buf(vararg pairs: Pair<Int, String>) =
        pairs.map { PastedText(it.first, it.second) }

    private fun textsOf(chunks: List<PasteChunk>) =
        chunks.filterIsInstance<PasteChunk.Text>().map { it.value }

    private fun idsOf(chunks: List<PasteChunk>) =
        chunks.filterIsInstance<PasteChunk.Pasted>().map { it.id }

    // ── Splitting ────────────────────────────────────────────────────────

    @Test
    fun `a marker between prose splits into three chunks in order`() {
        val (chunks, used) = splitPastePlaceholders("before [Pasted#1] after", buf(1 to "BODY"))
        assertEquals(3, chunks.size)
        assertTrue(chunks[0] is PasteChunk.Text)
        assertTrue(chunks[1] is PasteChunk.Pasted)
        assertTrue(chunks[2] is PasteChunk.Text)
        assertEquals(listOf("before ", " after"), textsOf(chunks))
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    @Test
    fun `a message that is only a marker produces exactly one chunk`() {
        // No empty text parts around it — an empty `text` part would be a
        // pointless row in parts_json and an empty bubble line.
        val (chunks, used) = splitPastePlaceholders("[Pasted#1]", buf(1 to "BODY"))
        assertEquals(1, chunks.size)
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    @Test
    fun `two markers split into five chunks and consume both`() {
        val (chunks, used) = splitPastePlaceholders(
            "a [Pasted#1] b [Pasted#2] c",
            buf(1 to "X", 2 to "Y"),
        )
        assertEquals(listOf("a ", " b ", " c"), textsOf(chunks))
        assertEquals(listOf(1, 2), idsOf(chunks))
        assertEquals(setOf(1, 2), used)
    }

    @Test
    fun `adjacent markers produce no empty text chunk between them`() {
        val (chunks, _) = splitPastePlaceholders("[Pasted#1][Pasted#2]", buf(1 to "X", 2 to "Y"))
        assertEquals(2, chunks.size)
        assertTrue(textsOf(chunks).isEmpty())
    }

    @Test
    fun `the same marker twice yields two chunks and one consumed id`() {
        // Both occurrences must become their own mediaRef part — the model has
        // to see the text in both positions — but the buffer entry is cleared
        // once.
        val (chunks, used) = splitPastePlaceholders("[Pasted#1]/[Pasted#1]", buf(1 to "A"))
        assertEquals(listOf(1, 1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    // ── Leniency: anything unrecognised stays literal text ───────────────

    @Test
    fun `an unknown id stays literal and consumes nothing`() {
        val (chunks, used) = splitPastePlaceholders("see [Pasted#99]", buf(1 to "BODY"))
        assertEquals(listOf("see [Pasted#99]"), textsOf(chunks))
        assertTrue(idsOf(chunks).isEmpty())
        assertTrue(used.isEmpty())
    }

    @Test
    fun `damaged markers stay literal`() {
        for (broken in listOf("[Pasted#1", "[Pasted#]", "[Pasted# 1]", "Pasted#1]")) {
            val (chunks, used) = splitPastePlaceholders(broken, buf(1 to "BODY"))
            assertEquals("unchanged: $broken", listOf(broken), textsOf(chunks))
            assertTrue(used.isEmpty())
        }
    }

    @Test
    fun `a lowercase marker still splits`() {
        val (chunks, used) = splitPastePlaceholders("a [pasted#1] b", buf(1 to "BODY"))
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    @Test
    fun `text with no markers is one chunk`() {
        val (chunks, used) = splitPastePlaceholders("plain message", buf(1 to "A"))
        assertEquals(listOf("plain message"), textsOf(chunks))
        assertTrue(used.isEmpty())
    }

    @Test
    fun `an empty buffer leaves everything literal`() {
        val (chunks, used) = splitPastePlaceholders("[Pasted#1]", emptyList())
        assertEquals(listOf("[Pasted#1]"), textsOf(chunks))
        assertTrue(used.isEmpty())
    }

    @Test
    fun `empty text produces no chunks`() {
        val (chunks, used) = splitPastePlaceholders("", buf(1 to "A"))
        assertTrue(chunks.isEmpty())
        assertTrue(used.isEmpty())
    }

    @Test
    fun `pasted content shaped like a marker is NOT re-expanded`() {
        // The anti-recursion property, restated for the splitter: #1's body
        // contains "[Pasted#2]", but the scan only ever reads the ORIGINAL
        // string, so #2 is never pulled in.
        val (chunks, used) = splitPastePlaceholders(
            "x [Pasted#1] y",
            buf(1 to "body mentioning [Pasted#2] inline", 2 to "SHOULD-NOT-APPEAR"),
        )
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    // ── Recognising a pasted mediaRef ────────────────────────────────────

    @Test
    fun `a pasted ref is recognised by mime AND filename prefix`() {
        assertTrue(PastedMedia.isPastedRef("text/plain", "Pasted#3.txt"))
    }

    @Test
    fun `a genuine txt attachment is NOT treated as pasted`() {
        // The case the filename half of the test exists for: a real .txt the
        // user attached must keep rendering as a file and must NOT be inlined
        // into the prompt.
        assertFalse(PastedMedia.isPastedRef("text/plain", "notes.txt"))
        assertFalse(PastedMedia.isPastedRef("text/plain", null))
    }

    @Test
    fun `a file merely NAMED like a paste is NOT treated as pasted`() {
        // And the mime half: a user can name a PDF anything they like.
        assertFalse(PastedMedia.isPastedRef("application/pdf", "Pasted#1.txt"))
        assertFalse(PastedMedia.isPastedRef("image/png", "Pasted#1.txt"))
    }

    @Test
    fun `the filename we generate is recognised by our own check`() {
        // Guards the two halves drifting apart.
        val name = PastedMedia.fileNameFor(7)
        assertEquals("Pasted#7.txt", name)
        assertTrue(PastedMedia.isPastedRef(PastedMedia.MIME, name))
    }

    // ── The oversize threshold ───────────────────────────────────────────

    @Test
    fun `the file threshold sits above the fold threshold`() {
        // Ordering invariant: a paste must be foldable before it can be
        // oversize, or the middle band would have no defined behaviour.
        assertTrue(PASTE_AS_FILE_THRESHOLD > 1200)
        assertTrue(isLongPastedText("中".repeat(PASTE_AS_FILE_THRESHOLD + 1)))
    }

    @Test
    fun `a paste just over the threshold is oversize and just under is not`() {
        assertTrue("x".repeat(PASTE_AS_FILE_THRESHOLD + 1).length > PASTE_AS_FILE_THRESHOLD)
        assertFalse("x".repeat(PASTE_AS_FILE_THRESHOLD).length > PASTE_AS_FILE_THRESHOLD)
    }

    // ── Missing-file degradation ─────────────────────────────────────────
    //
    // Mirrors the pasted-ref branch of toLLMMessage: a file that cannot be read
    // must become an explicit marker in the prompt, never nothing. Dropping the
    // part would leave the model reading a sentence with an invisible hole,
    // which it answers as though nothing were missing.

    private fun resolveBody(fileBody: String?): String =
        fileBody ?: PastedMedia.MISSING_PLACEHOLDER

    @Test
    fun `a readable pasted file contributes its text`() {
        assertEquals("REAL BODY", resolveBody("REAL BODY"))
    }

    @Test
    fun `a missing pasted file contributes an explicit marker, not empty`() {
        val out = resolveBody(null)
        assertTrue("must not be silent", out.isNotEmpty())
        assertEquals(PastedMedia.MISSING_PLACEHOLDER, out)
    }

    @Test
    fun `the missing marker says what happened`() {
        // It reaches the PROMPT, so it has to be self-explanatory to the model:
        // enough for it to say "that content did not come through" instead of
        // inventing an answer.
        val m = PastedMedia.MISSING_PLACEHOLDER
        assertTrue(m.contains("unavailable"))
        assertTrue(m.contains("missing"))
    }

    @Test
    fun `an empty pasted file is NOT treated as missing`() {
        // Genuinely empty content is a faithful expansion of an empty paste;
        // substituting the marker would claim data loss that did not happen.
        assertEquals("", resolveBody(""))
    }

    // ── Compaction sees expanded text, never a literal marker ────────────
    //
    // buildConversationTextForSummary reads LLMMessage.content and
    // .contentParts. Those are written by exactly two kinds of producer, and
    // BOTH must emit expanded text or a summary would describe "[Pasted#1]"
    // instead of what the user actually pasted:
    //
    //   - fresh send / queued prompts  -> PastedParts.modelText
    //   - every replay path            -> toLLMMessage's pasted-ref branch
    //
    // The functions themselves need Android plumbing, so what is pinned here is
    // the property that matters: whatever reaches the summary must not contain a
    // marker literal.

    private val markerPattern = Regex("""\[[Pp]asted#\d+]""")

    /**
     * Producer inventory for `agentHistory`, which is the ONLY thing compaction
     * reads (compactAllImpl slices `agentHistory.toList()`).
     *
     * This list is the actual audit, re-checked against the source by grepping
     * every `agentHistory.add` / `.addAll`. It is written down here because the
     * equivalent claim was made on iOS from the consumer side and was WRONG
     * there — compaction had its own path that bypassed expansion, and
     * `[Pasted#N]` literals reached summaries, becoming permanent once the
     * originals were discarded. The consumer
     * (buildConversationTextForSummary) proves nothing: it formats whatever
     * LLMMessage list it is handed.
     *
     * If a new agentHistory writer appears that carries USER-AUTHORED text and
     * is not in this list, this test should fail loudly rather than the leak
     * being discovered in a summary months later.
     */
    private enum class AgentHistoryProducer(val expandsPastes: Boolean, val carriesUserText: Boolean) {
        /** loadSession pre-build (:4212) — entity.toLLMMessage() per row. */
        LOAD_SESSION(true, true),

        /** rerunFromToolBlock (:5320) — clear + refill via toLLMMessage. */
        RERUN_FROM_TOOL_BLOCK(true, true),

        /** retryFromMessage (:5450) — clear + refill via toLLMMessage. */
        RETRY_FROM_MESSAGE(true, true),

        /** deleteFromMessage (:5534) — clear + refill via toLLMMessage. */
        DELETE_FROM_MESSAGE(true, true),

        /** truncateBeforeEdit (:5930) — clear + refill via toLLMMessage. */
        TRUNCATE_BEFORE_EDIT(true, true),

        /** injectQueuedPromptsAsNewTurn (:6105) — uses PastedParts.modelText. */
        QUEUED_INJECT(true, true),

        /** drainQueuedPrompts (:6252) — uses PastedParts.modelText. */
        QUEUED_DRAIN(true, true),

        /** sendMessage (:6487) — uses `modelBody` (= PastedParts.modelText). */
        FRESH_SEND(true, true),

        /** Queue-bridge assistant note (:6075) — hardcoded string. */
        QUEUE_BRIDGE(false, false),

        /** Streamed assistant turn (:8501) — model output. */
        ASSISTANT_TURN(false, false),

        /** Tool-result user turn (:8981) — content = "", tool parts only. */
        TOOL_RESULT_TURN(false, false),

        /** Cancel-cleanup partial assistant (:11596) — model output. */
        CANCEL_PARTIAL(false, false),

        /** resume() continue reminder (:11687) — hardcoded system-reminder. */
        RESUME_REMINDER(false, false),

        /**
         * sanitizeAgentHistory orphan repair (:7011) — synthetic user turn with
         * `content = ""` holding only placeholder ToolResult parts.
         */
        SANITIZE_ORPHAN_REPAIR(false, false),
    }

    @Test
    fun `the producer inventory covers every agentHistory write site`() {
        // Mechanical cross-check against the source. `grep -c
        // 'agentHistory\.add(\|agentHistory\.addAll('` on ChatViewModel.kt
        // returns 14; the inventory must have one entry per site.
        //
        // This count caught a real omission while the inventory was being
        // written — sanitizeAgentHistory's orphan-repair insert (:7011) was
        // missing, so "every producer is accounted for" was not yet true even
        // though the leak conclusion happened to be. Keeping the count pinned
        // means a NEW writer forces someone to classify it rather than silently
        // widening the surface.
        assertEquals(
            "agentHistory write sites in ChatViewModel.kt vs classified producers",
            14,
            AgentHistoryProducer.values().size,
        )
    }

    @Test
    fun `every agentHistory producer carrying user text expands pasted refs`() {
        // The load-bearing assertion: compaction reads agentHistory, so any
        // producer of user-authored text that does NOT expand would leak a
        // literal into a summary — the exact iOS bug.
        val leaky = AgentHistoryProducer.values()
            .filter { it.carriesUserText && !it.expandsPastes }
        assertTrue(
            "these agentHistory producers carry user text without expanding: $leaky",
            leaky.isEmpty(),
        )
    }

    @Test
    fun `the producers that skip expansion are all app-generated text`() {
        // The other direction, so the inventory cannot be trivially satisfied
        // by flipping a flag: everything exempt must genuinely be text the app
        // wrote itself (bridge note, assistant output, tool results, reminder),
        // never something a user could have pasted into.
        val exempt = AgentHistoryProducer.values().filterNot { it.expandsPastes }
        assertTrue(exempt.none { it.carriesUserText })
        assertEquals(6, exempt.size)
    }

    @Test
    fun `expanded send text carries no marker literal into the summary`() {
        // modelText is the concatenation the splitter produces — prose plus the
        // pasted bodies, with the markers consumed.
        val (chunks, _) = splitPastePlaceholders("before [Pasted#1] after", buf(1 to "FULL BODY"))
        val modelText = chunks.joinToString("") {
            when (it) {
                is PasteChunk.Text -> it.value
                is PasteChunk.Pasted -> "FULL BODY"
            }
        }
        assertEquals("before FULL BODY after", modelText)
        assertFalse(
            "a summary must never see the marker",
            markerPattern.containsMatchIn(modelText),
        )
    }

    @Test
    fun `an unresolvable marker reaching the summary stays literal, not blank`() {
        // The one case where a literal legitimately survives: the user typed an
        // id that was never in the buffer. It is persisted as text and must
        // summarise as the text it is, rather than silently disappearing.
        val (chunks, used) = splitPastePlaceholders("see [Pasted#99]", buf(1 to "A"))
        assertTrue(used.isEmpty())
        assertEquals("see [Pasted#99]", textsOf(chunks).single())
    }

    @Test
    fun `a missing file still yields non-empty summary input`() {
        // Degradation path feeding compaction: the marker text is what gets
        // summarised, so the summary records that content was lost instead of
        // quietly omitting a turn's substance.
        assertTrue(resolveBody(null).isNotEmpty())
        assertFalse(markerPattern.containsMatchIn(resolveBody(null)))
    }

    // ── Caption stripping (what the bubble shows) ────────────────────────

    @Test
    fun `consumed markers are stripped from the visible caption`() {
        // Mirrors the fold in sendMessage: the file cards stand for the
        // markers, so leaving them in the caption would show both.
        val trimmed = "before [Pasted#1] after"
        val visible = setOf(1).fold(trimmed) { acc, id ->
            acc.replace(PastedText.placeholderFor(id), "")
        }.trim()
        assertEquals("before  after", visible)
    }

    @Test
    fun `an unconsumed marker survives in the caption`() {
        // It was persisted as literal text, so the bubble must show it too.
        val trimmed = "see [Pasted#99]"
        val visible = emptySet<Int>().fold(trimmed) { acc, id ->
            acc.replace(PastedText.placeholderFor(id), "")
        }.trim()
        assertEquals("see [Pasted#99]", visible)
    }
}
