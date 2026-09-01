package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * [T-android-paste-placeholder] A long block of text the user pasted, held out
 * of the composer and represented there by a `[Pasted#N]` literal.
 *
 * Pasting a wall of text used to dump the whole thing into the input field,
 * which buried the caret, made the field scroll for pages, and left the user
 * unable to see what they were actually typing. The text now lives here and
 * the composer shows a short marker instead; [expandPastePlaceholders] puts it
 * back at send time, so the model and the stored history both see the full
 * text and nothing about the wire format changes.
 *
 * Memory-only, per ViewModel (i.e. per session), deliberately not persisted:
 * the Compose composer's own draft text is not persisted either, so buffer and
 * draft share one lifetime and a restart clears both together. Persisting only
 * one of the two is what would create a broken state — a draft full of
 * `[Pasted#N]` markers with nothing to expand them to.
 */
data class PastedText(
    val id: Int,
    val text: String,
) {
    /** Marker written into the composer for this entry. */
    val placeholder: String get() = placeholderFor(id)

    /**
     * First line, clipped — what the chip shows so the user can tell two
     * pasted blocks apart without opening anything.
     */
    val preview: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(60)

    companion object {
        fun placeholderFor(id: Int): String = "[Pasted#$id]"
    }
}

/**
 * [T-android-paste-placeholder] Matches a placeholder literal.
 *
 * `\d+` only — no whitespace, no sign, no leading zeros special-casing. A
 * damaged marker (`[Pasted#3`, `[Pasted#]`, `[Pasted# 3]`) simply does not
 * match and is left in the text verbatim, which is the required behaviour for
 * "user hand-edited the marker": never crash, never guess.
 *
 * The leading letter accepts either case. We only ever WRITE `[Pasted#N]`, but
 * a user retyping a marker by hand is far more likely to type it lowercase,
 * and silently failing to expand their text would look like data loss. Reading
 * loosely while writing strictly costs one character in the pattern.
 */
private val PLACEHOLDER_REGEX =
    Regex("""\[[Pp]asted#(\d+)]""")

/**
 * [T-android-paste-placeholder] Replace every `[Pasted#N]` with its buffered
 * text, in ONE pass.
 *
 * Single-pass is the load-bearing property, not an optimisation. If pasted
 * content itself contains something shaped like `[Pasted#2]` — which is
 * exactly what happens when a user pastes a transcript of this feature being
 * discussed — a recursive or repeated-replace implementation would expand that
 * inner literal too, injecting unrelated buffered text into the message. Here
 * the scan reads the ORIGINAL string once and only ever consults it for match
 * positions, so expanded content is never re-scanned.
 *
 * Unknown ids are left verbatim: a hand-typed `[Pasted#99]` is just text, and
 * a send must never fail because of one. That also covers the entry the user
 * deleted from the buffer but left the marker for.
 *
 * @return the expanded text plus the ids that were actually consumed, so the
 *   caller can drop exactly those entries and leave orphans alone.
 */
fun expandPastePlaceholders(
    text: String,
    buffer: List<PastedText>,
): Pair<String, Set<Int>> {
    if (text.isEmpty() || buffer.isEmpty()) return text to emptySet()
    val byId = buffer.associateBy { it.id }
    val consumed = mutableSetOf<Int>()
    val out = StringBuilder(text.length)
    var cursor = 0
    for (match in PLACEHOLDER_REGEX.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull()
        val entry = id?.let { byId[it] } ?: continue // unknown / overflowed → leave verbatim
        out.append(text, cursor, match.range.first)
        out.append(entry.text)
        cursor = match.range.last + 1
        consumed += entry.id
    }
    if (consumed.isEmpty()) return text to emptySet()
    out.append(text, cursor, text.length)
    return out.toString() to consumed
}

/**
 * [T-android-paste-mediaref] One piece of a message body after the
 * `[Pasted#N]` markers have been cut out of it.
 *
 * A message like `look at this [Pasted#1] what do you think` becomes
 * `Chunk.Text("look at this ")`, `Chunk.Pasted(1)`, `Chunk.Text(" what do you
 * think")` — which maps one-to-one onto the parts array we persist, so the
 * pasted block lands as its own `mediaRef` part instead of being inlined into a
 * multi-thousand-character `text` part.
 */
sealed class PasteChunk {
    data class Text(val value: String) : PasteChunk()

    /** A folded paste, identified by its buffer id. */
    data class Pasted(val id: Int) : PasteChunk()
}

/**
 * [T-android-paste-mediaref] Split [text] on its `[Pasted#N]` markers.
 *
 * This replaces the old "expand inline at send time" step. Expanding produced a
 * single enormous `text` part that then had to be laid out by TextKit whenever
 * the message scrolled into view — the same main-thread stall that made huge
 * tool results freeze the app. Splitting keeps the bubble small; the full text
 * is re-attached to the request later, from disk.
 *
 * Same single-pass scan and same leniency as [expandPastePlaceholders]: an
 * unknown id is left as literal text rather than dropped, because a hand-typed
 * `[Pasted#99]` is just something the user wrote.
 *
 * Adjacent/empty text runs are omitted so a message that is nothing but a marker
 * produces exactly one part.
 *
 * @return the chunks plus the ids actually referenced, so the caller can clear
 *   precisely those buffer entries.
 */
fun splitPastePlaceholders(
    text: String,
    buffer: List<PastedText>,
): Pair<List<PasteChunk>, Set<Int>> {
    if (text.isEmpty() || buffer.isEmpty()) {
        return listOf<PasteChunk>(PasteChunk.Text(text)).filterNot {
            it is PasteChunk.Text && it.value.isEmpty()
        } to emptySet()
    }
    val byId = buffer.associateBy { it.id }
    val consumed = mutableSetOf<Int>()
    val chunks = mutableListOf<PasteChunk>()
    var cursor = 0
    for (match in PLACEHOLDER_REGEX.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull()
        if (id == null || !byId.containsKey(id)) continue // unknown → stays literal
        val before = text.substring(cursor, match.range.first)
        if (before.isNotEmpty()) chunks.add(PasteChunk.Text(before))
        chunks.add(PasteChunk.Pasted(id))
        cursor = match.range.last + 1
        consumed += id
    }
    if (consumed.isEmpty()) {
        return listOf<PasteChunk>(PasteChunk.Text(text)).filterNot {
            it is PasteChunk.Text && it.value.isEmpty()
        } to emptySet()
    }
    val tail = text.substring(cursor)
    if (tail.isNotEmpty()) chunks.add(PasteChunk.Text(tail))
    return chunks to consumed
}

/**
 * [T-android-paste-mediaref] Naming and typing convention that marks a
 * `mediaRef` part as "this was a pasted block", without touching the DB schema.
 *
 * The spec's hard constraint is no new column and no new table. `parts_json` is
 * already a free-form array and `mediaRef` already carries arbitrary text/image
 * /document payloads, so the only thing missing is a way to tell a pasted block
 * apart from a file the user actually picked — the two must render differently
 * and expand differently in the request.
 *
 * The discriminator is [PASTED_FILENAME_PREFIX] on `originalFileName`, paired
 * with `text/plain`. Filename was chosen over a bespoke MIME subtype because
 * every existing consumer already reads `originalFileName` and treats it as an
 * opaque label — a `text/x-minis-pasted` MIME would instead have to be taught to
 * the file-icon mapper, the preview router and the share sheet, any one of which
 * would show something wrong if missed.
 */
object PastedMedia {
    /** Marks a mediaRef as pasted text. Also what the user sees as the filename. */
    const val PASTED_FILENAME_PREFIX = "Pasted#"

    const val MIME = "text/plain"

    /**
     * [T-android-paste-missing-file] Stands in for a pasted block whose file is
     * gone at request-build time.
     *
     * Explicit over silent: an omitted part leaves the model reading a sentence
     * with an invisible hole, which it will answer as though nothing were
     * missing. Phrased for the model rather than the user, since this string
     * only ever reaches the prompt.
     */
    const val MISSING_PLACEHOLDER =
        "[pasted content unavailable — the stored text file is missing]"

    /** `Pasted#3.txt` — recognisable in a file listing and traceable to the marker. */
    fun fileNameFor(id: Int): String = "$PASTED_FILENAME_PREFIX$id.txt"

    /**
     * Whether a persisted mediaRef came from a folded paste.
     *
     * Both conditions matter: the prefix alone would misclassify a real file a
     * user happened to name `Pasted#1.txt`, and `text/plain` alone would swallow
     * every genuine .txt attachment — which must keep rendering and behaving as
     * an ordinary file.
     */
    fun isPastedRef(mimeType: String?, originalFileName: String?): Boolean =
        mimeType == MIME &&
            originalFileName != null &&
            originalFileName.startsWith(PASTED_FILENAME_PREFIX)
}

/**
 * [T-android-paste-oversize] Above this many characters a paste stops being a
 * placeholder and becomes a real `.txt` file attachment instead.
 *
 * The placeholder path keeps the whole block in memory and writes it into the
 * message's own media file on send; that is fine for a few thousand characters
 * and wrong for a novel. Past this size the user is really attaching a document,
 * so it takes the ordinary attachment path and gains everything that comes with
 * it — preview, removal, the upload inventory the model can `cat`.
 */
const val PASTE_AS_FILE_THRESHOLD = 15_000

/**
 * [T-android-paste-placeholder] True when [inserted] is long enough to fold
 * into a placeholder.
 *
 * Mirrors iOS `ChatInputBar.paste(_:)` exactly, including the language split:
 * English prose carries far more characters per unit of meaning than CJK, so a
 * single character threshold would either fold short English paragraphs or let
 * long Chinese ones through. ASCII letters > half the length is the same
 * "English-dominant" test iOS uses.
 *
 *   English-dominant → more than 1000 whitespace-separated words
 *   otherwise (CJK / mixed) → more than 1200 characters
 */
fun isLongPastedText(inserted: String): Boolean {
    val asciiLetters = inserted.count { it in 'A'..'Z' || it in 'a'..'z' }
    val englishDominant = asciiLetters > inserted.length / 2
    return if (englishDominant) {
        inserted.split(' ', '\n', '\t', '\r').count { it.isNotEmpty() } > 1000
    } else {
        inserted.length > 1200
    }
}

/**
 * [T-android-paste-placeholder] If [new] differs from [old] by one large
 * inserted run, replace that run with a `[Pasted#N]` marker.
 *
 * Returns [new] unchanged when the edit is not a big insertion, so ordinary
 * typing, deleting and IME composition flow through untouched.
 *
 * How the inserted span is located: common prefix from the left, common suffix
 * from the right, and whatever remains in the middle is what changed. That
 * handles a paste at any caret position — start, middle, end — and, unlike a
 * naive "the new text ends with the old text" check, it also handles pasting
 * over a selection (the replaced text disappears from both ends at once).
 *
 * Why length delta rather than the raw new length: the test must fire on the
 * INSERTED run, not the field's total size. Otherwise, once the field already
 * held a long block, every subsequent keystroke would re-trigger.
 *
 * Deliberate non-goals:
 *  - It cannot distinguish a clipboard paste from an IME committing a large
 *    block (some voice keyboards do this). That is fine: a wall of text is
 *    worth folding whichever way it arrived.
 *  - A multi-run edit (rare: some autocorrect implementations) falls back to
 *    treating the whole middle as one span, which is still correct — the user
 *    sees one marker for one large change.
 */
fun foldLongPasteIfNeeded(
    old: TextFieldValue,
    new: TextFieldValue,
    stash: (String) -> String,
): TextFieldValue {
    val oldText = old.text
    val newText = new.text
    val delta = newText.length - oldText.length
    // Cheap gate first: a deletion or a small edit can never be a long paste,
    // and this is the branch nearly every keystroke takes.
    if (delta <= 0) return new

    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

    var suffix = 0
    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    while (
        suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++

    val insertedStart = prefix
    val insertedEnd = newText.length - suffix
    if (insertedEnd <= insertedStart) return new
    val inserted = newText.substring(insertedStart, insertedEnd)
    if (!isLongPastedText(inserted)) return new

    val marker = stash(inserted)
    val folded = newText.substring(0, insertedStart) + marker + newText.substring(insertedEnd)
    // Caret goes just after the marker — where the user's next keystroke
    // belongs, and what they would expect had they typed the marker by hand.
    val caret = insertedStart + marker.length
    return TextFieldValue(
        text = folded,
        selection = TextRange(caret),
    )
}


/**
 * [T-android-paste-chip-square] Chip for one folded paste — same 64dp square
 * geometry as [AttachmentChip]'s file tile, so the composer shows one visual
 * language instead of two.
 *
 * It used to be a wide pill with a text preview, which made a pasted block look
 * like a different KIND of thing from an attached file when it is really the
 * same idea: content held outside the text field, previewable, removable. The
 * pill also grew with its preview, so a row mixing pastes and files had chips at
 * two different heights.
 *
 * Geometry is copied deliberately rather than shared: the 72×70 outer box, the
 * 64dp body at BottomStart and the 20dp badge at TopEnd are what let the remove
 * button hang half-off the corner without being clipped. Any drift here shows up
 * as a misaligned row.
 *
 * The label is the id and the character count — the two things that tell pastes
 * apart at a glance. The first-line preview it used to show does not survive the
 * narrower tile, and tapping still opens the full text.
 */
@Composable
fun PastedTextChip(
    pasted: PastedText,
    onRemove: () -> Unit,
) {
    var showPreview by remember(pasted.id) { mutableStateOf(false) }
    val chipShape = RoundedCornerShape(8.dp)
    Box(modifier = Modifier.size(width = 72.dp, height = 70.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                .clip(chipShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, chipShape)
                .border(1.dp, ChatColors.thumbnailBorder, chipShape)
                .clickable { showPreview = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                // Text-file glyph: this IS a block of text, and on send it
                // becomes a text/plain mediaRef — the same icon the message
                // bubble will show for it afterwards.
                Icons.AutoMirrored.Filled.TextSnippet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "#${pasted.id}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "${pasted.text.length}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Remove badge, matching AttachmentChip: half on / half off the
        // top-right corner, hairline border so it stays legible.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(0.5.dp, ChatColors.thumbnailBorder, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
        }
    }

    if (showPreview) {
        PastedTextPreviewDialog(pasted = pasted, onDismiss = { showPreview = false })
    }
}

/**
 * [T-android-paste-placeholder] Read-only full-text preview.
 *
 * Read-only on purpose: the buffered text is what will be sent verbatim, and
 * an editable sheet would raise questions this feature has no answer for —
 * whether an edit re-runs the length threshold, what happens to a marker whose
 * content shrank below it, and how an edit interacts with the same entry being
 * referenced twice. Viewing is the need users actually have ("what did I
 * paste?"); editing is a separate feature with its own design.
 *
 * A plain Dialog rather than MinisAlertDialog because the body must SCROLL —
 * these are thousand-character blocks by definition, and MinisAlertDialog
 * renders `text` as a fixed Text that would overflow the screen.
 */
@Composable
private fun PastedTextPreviewDialog(
    pasted: PastedText,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "${pasted.placeholder}  ·  ${pasted.text.length}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pasted.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        // Capped so a huge paste cannot push the close button
                        // off-screen; the body scrolls within the cap.
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    // fillMaxWidth is what makes Arrangement.End mean anything:
                    // without it the Row wraps to the button's own width, so
                    // "End" aligns the button inside a box exactly its size and
                    // it renders flush LEFT — which is what shipped.
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}
