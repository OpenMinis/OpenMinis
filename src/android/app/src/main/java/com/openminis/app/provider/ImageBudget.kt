package com.openminis.app.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream

/**
 * Per-message inline-image byte budget — mirrors iOS AIChatViewModel.swift
 * kPerImageMaxBytes / kMessageImageMaxBytes (commit b830360).
 *
 * Anthropic returns HTTP 413 ("Downloaded image content cannot exceed 30MB")
 * when the request's total inline image payload exceeds the provider cap.
 * Pre-T-imgsize we only resized each attachment to a max-edge of 2000px at
 * JPEG q=85, which is *resolution* budgeting and does nothing for byte size
 * when the source is a 12MP HEIC. We now enforce a real byte cap at two
 * layers:
 *
 *   1. Composer (ChatViewModel.prepareUserAttachments) — runs the user's
 *      brand-new attachments through [compressUnderBudget] so every part is
 *      ≤ MAX_PER_IMAGE_BYTES, then tallies cumulative bytes and drops the
 *      tail with a Snackbar notice once MAX_TOTAL_BYTES is exceeded.
 *      Both caps are measured in base64 bytes — see [estimatedBase64Length].
 *   2. Provider boundary (AnthropicProvider / OpenAIProvider) — belt and
 *      braces for history image parts that bypass the composer (e.g. tool
 *      results screenshot bytes, restored sessions, retry-after-edit). Each
 *      oversize part is silently re-encoded in-place via [compressBytes].
 *
 * URL HEAD pre-check (spec §2.b) intentionally omitted — both Android
 * providers always base64-inline image bytes (no remote URL forwarding),
 * mirroring the iOS finding in commit b830360.
 */
object ImageBudget {
    /**
     * [T-image-budget-base64] Every cap below is a budget on the bytes that
     * actually leave the device, and images leave base64-inlined inside the
     * request JSON — never as raw bytes. Base64 costs 4 bytes per 3, so a
     * budget policed against raw sizes silently permits ~1.333x what it
     * claims: 25 MB of raw image is ~33.3 MB on the wire, past the 32 MB
     * gateway limit that reported "request too large" before the text
     * history and tool schemas are even counted.
     *
     * So the constants keep their values and their meaning — a ceiling on
     * transmitted bytes — and every comparison converts the raw size with
     * [estimatedBase64Length] first. Chosen over simply scaling the numbers
     * down by 1/1.333 because the expansion applies per image: clamping N
     * images each just under a scaled cap accumulates N rounding errors,
     * while converting at the comparison is exact and leaves one obvious
     * place to reason about.
     */
    fun estimatedBase64Length(rawBytes: Int): Long = estimatedBase64Length(rawBytes.toLong())

    /** Long overload — request-level tallies sum well past Int range. */
    fun estimatedBase64Length(rawBytes: Long): Long = ((rawBytes + 2) / 3) * 4

    /**
     * Single image ceiling, in transmitted (base64) bytes.
     *
     * NOTE the asymmetry with [compressUnderBudget]: the ladder there
     * measures the *raw* JPEG it produces, so callers pass it a raw-byte
     * target derived from this cap, not the cap itself.
     */
    const val MAX_PER_IMAGE_BYTES = 5L * 1024 * 1024

    /**
     * The raw-byte target that encodes to [MAX_PER_IMAGE_BYTES]. The
     * compressor works in raw bytes, so the base64 budget has to be
     * converted back before it can be used as a compression goal.
     */
    const val MAX_PER_IMAGE_RAW_BYTES = MAX_PER_IMAGE_BYTES / 4 * 3

    /** Cumulative inline-image bytes per user message, base64-measured. */
    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024

    /**
     * Cumulative inline-image bytes across ALL messages in a single
     * request body. Mirrors the per-message cap but applies at the
     * request boundary so a long history accumulating images from
     * multiple turns (browser screenshots, attachments, read_image
     * results) cannot push the request past the cap that triggered
     * factory.pub / Anthropic gateways to silently return 200 +
     * empty SSE with `finish_reason=stop`. Eldest images are elided
     * to text placeholders first.
     *
     * Base64-measured, like the caps above.
     */
    const val MAX_REQUEST_BYTES = 25L * 1024 * 1024

    /** Default re-encode target longest edge in pixels. */
    const val MAX_EDGE_PX = 2000

    /** Default re-encode JPEG quality (0-100). */
    const val JPEG_QUALITY = 80

    private const val TAG = "ImageBudget"

    /**
     * (maxEdge, quality) candidates the per-image compressor walks until the
     * output fits under [MAX_PER_IMAGE_RAW_BYTES]. Mirrors the iOS ladder from
     * AIChatViewModel.swift compressedImageDataUnderBudget(...).
     */
    private val LADDER: List<Pair<Int, Int>> = listOf(
        2000 to 80,
        1600 to 75,
        1280 to 70,
        1024 to 65,
        896 to 55,
        768 to 50,
        640 to 45,
    )

    /**
     * Re-encode [input] to a JPEG with max longest edge [maxEdge] at JPEG
     * quality [q]. Returns the original bytes on decode/encode failure
     * (caller's existing payload is always safer than dropping the image).
     */
    fun compressBytes(input: ByteArray, maxEdge: Int = MAX_EDGE_PX, q: Int = JPEG_QUALITY): ByteArray {
        if (input.isEmpty()) return input
        return try {
            // Two-pass decode mirroring PhotosOffloadHandler.copyResized — sampled
            // bounds first to keep the in-memory bitmap proportional to maxEdge,
            // then scaled to the exact target after decode.
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, boundsOpts)
            val w0 = boundsOpts.outWidth
            val h0 = boundsOpts.outHeight
            if (w0 <= 0 || h0 <= 0) return input
            var sample = 1
            while (w0 / sample > maxEdge * 2 || h0 / sample > maxEdge * 2) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, decodeOpts)
                ?: return input
            val scale = minOf(
                maxEdge.toFloat() / decoded.width,
                maxEdge.toFloat() / decoded.height,
                1f,
            )
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else decoded
            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, q.coerceIn(1, 100), baos)
            if (out !== decoded) out.recycle()
            decoded.recycle()
            baos.toByteArray()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "compressBytes failed (${input.size}B → keeping original): ${t.message}")
            input
        }
    }

    // -- [T-android-image-downscale-parity] One ≤MAX_EDGE_PX copy for the model --

    /** An image fitted within [MAX_EDGE_PX] for the model, with both sizes for the note. */
    class Downscaled(
        val bytes: ByteArray,
        val mimeType: String,
        val originalWidth: Int,
        val originalHeight: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Target size when [w]×[h] must fit within [maxEdge] on its longest side,
     * or null when it already fits (or the size is unknown). Pure, so the
     * arithmetic is testable without a Bitmap.
     */
    internal fun fitWithin(w: Int, h: Int, maxEdge: Int): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return null
        val scale = maxEdge.toDouble() / longest
        return Pair(
            maxOf(1, Math.round(w * scale).toInt()),
            maxOf(1, Math.round(h * scale).toInt()),
        )
    }

    /**
     * [T-android-image-downscale-parity] Fit an image within [maxEdge] for the
     * model. The ONE downscale used both when a message is first sent and when
     * its history is rebuilt from the saved original after a restart, so every
     * turn resends the same reduced image — what iOS does by persisting the
     * reduced copy it sent (AIChatViewModel+Persistence saveMedia).
     *
     * Field report: DeepSeek 400 "unsupported image" on every turn after a
     * restart for a 1264×23545 long screenshot. First send fitted it within
     * 2000 px and worked; the reload path replayed the full-size original (the
     * byte budget only shrinks images over 3.75 MB, this one was 1.6 MB) and
     * DeepSeek rejects an image that tall, reporting it as a format problem.
     *
     * Keeps PNG as PNG, everything else becomes JPEG q85 — the rule the old
     * composer resize used. Decodes with inSampleSize first so a long
     * screenshot is not materialised at full size (1264×23545 is ~119 MB as a
     * bitmap). Null when the image already fits or cannot be decoded.
     */
    fun downscaleForModel(input: ByteArray, maxEdge: Int = MAX_EDGE_PX): Downscaled? {
        if (input.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            val w0 = bounds.outWidth
            val h0 = bounds.outHeight
            val (tw, th) = fitWithin(w0, h0, maxEdge) ?: return null
            var sample = 1
            while (w0 / (sample * 2) >= tw && h0 / (sample * 2) >= th) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val scaled = Bitmap.createScaledBitmap(decoded, tw, th, true)
            val png = sniffFormat(input) == ImageFormat.PNG
            val baos = ByteArrayOutputStream()
            scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, baos)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            Downscaled(
                baos.toByteArray(),
                if (png) ImageFormat.PNG.mimeType!! else ImageFormat.JPEG.mimeType!!,
                w0, h0, tw, th,
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "downscaleForModel failed (${input.size}B): ${t.message}")
            null
        }
    }

    /**
     * Restored history images come from a file; key the cache by that file
     * (path + size + mtime) so rerun / retry / reload do not decode the same
     * long screenshot again. Only images that needed a downscale are cached.
     */
    private val restoredDownscaleCache = object : LinkedHashMap<String, Downscaled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Downscaled>?) = size > 16
    }

    fun downscaleForModel(file: java.io.File, bytes: ByteArray): Downscaled? {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(restoredDownscaleCache) { restoredDownscaleCache[key] }?.let { return it }
        val out = downscaleForModel(bytes) ?: return null
        synchronized(restoredDownscaleCache) { restoredDownscaleCache[key] = out }
        return out
    }

    /**
     * [T-android-image-downscale-parity] Text placed right after a downscaled
     * image so the model knows it is looking at a reduced copy of something
     * much larger — otherwise a 107 px wide sliver of a long screenshot reads
     * as a tiny image rather than as an unreadable one. iOS has no such note
     * (it only states dimensions in the omitted-image placeholder,
     * imagePlaceholderText); the W×H spelling follows that placeholder.
     * English-only by design: model-facing text, not UI.
     */
    fun downscaleNote(d: Downscaled, linuxPath: String?): String = buildString {
        append("[Image downscaled before sending: the original is ")
        append(d.originalWidth).append('×').append(d.originalHeight)
        append(" px, shown here at ").append(d.width).append('×').append(d.height)
        append(" px, so fine detail and small text may be unreadable.")
        if (!linuxPath.isNullOrBlank()) append(" The full-size original is saved at ").append(linuxPath).append('.')
        append(']')
    }

    // -- [T-android-image-upload-format] Format + MIME at the provider boundary --

    /**
     * What an image's bytes actually are, read from its magic number. Only the
     * four formats with a [mimeType] can be sent: they are the set DeepSeek
     * names in its 400 ("webp, png, jpeg, and gif") and the set OpenAI and
     * Anthropic accept too.
     */
    enum class ImageFormat(val mimeType: String?) {
        JPEG("image/jpeg"),
        PNG("image/png"),
        GIF("image/gif"),
        WEBP("image/webp"),
        HEIF(null),
        AVIF(null),
        BMP(null),
        TIFF(null),
        UNKNOWN(null);

        val isUploadable: Boolean get() = mimeType != null
    }

    fun sniffFormat(b: ByteArray): ImageFormat {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun ascii(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> ImageFormat.JPEG
            at(0) == 0x89 && ascii(1, "PNG") -> ImageFormat.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageFormat.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageFormat.WEBP
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> ImageFormat.AVIF
            ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
                .any { ascii(8, it) } -> ImageFormat.HEIF
            ascii(0, "BM") -> ImageFormat.BMP
            ascii(0, "II*\u0000") || ascii(0, "MM\u0000*") -> ImageFormat.TIFF
            else -> ImageFormat.UNKNOWN
        }
    }

    /** An image ready to inline: [mimeType] always describes [bytes] as they are. */
    class UploadImage(val bytes: ByteArray, val mimeType: String) {
        fun base64(): String = java.util.Base64.getEncoder().encodeToString(bytes)
        fun dataUrl(): String = "data:$mimeType;base64,${base64()}"
    }

    /** Sent in place of an image that cannot be turned into an accepted format. */
    const val UNSENDABLE_IMAGE_PLACEHOLDER =
        "[Image omitted: its file format could not be converted to one this model accepts]"

    /**
     * [T-android-image-upload-format] The single gate every provider inlines an
     * image through: guarantee an accepted FORMAT, then the byte budget, and
     * label the result with the MIME of the bytes that actually go out.
     *
     * Field report: DeepSeek answered every turn of a conversation with 400
     * "You have uploaded an unsupported image". Reproduced on a Pixel 4a with a
     * HEIC photo, on both paths:
     *  - first send: the composer had re-encoded the 3000 px HEIC to JPEG but
     *    kept the attachment's `image/heic` label — DeepSeek validates the
     *    declared MIME, so valid JPEG bytes were rejected;
     *  - after a restart: history is rebuilt from the saved original, so the
     *    raw HEIC went out, and the old boundary only re-encoded images over
     *    the size budget.
     * Retry resent the identical body, so the conversation could not recover.
     *
     * Returns null only when an unaccepted format cannot be decoded here; the
     * caller then sends [UNSENDABLE_IMAGE_PLACEHOLDER] instead of the pixels.
     */
    fun prepareForUpload(input: ByteArray, declaredMime: String?): UploadImage? =
        prepareForUpload(input, declaredMime, ::transcodeCached, ::compressUnderBudget, ::fitCached)

    internal fun prepareForUpload(
        input: ByteArray,
        declaredMime: String?,
        transcode: (ByteArray) -> ByteArray?,
        budget: (ByteArray) -> ByteArray,
        // [T-android-image-downscale-parity] Fit within MAX_EDGE_PX; null = already fits.
        fit: (ByteArray) -> ByteArray? = { null },
    ): UploadImage? {
        if (input.isEmpty()) return null
        val sourceFormat = sniffFormat(input)
        val accepted = if (sourceFormat.isUploadable) input else {
            transcode(input) ?: run {
                if (sourceFormat == ImageFormat.UNKNOWN) {
                    // Not a format we can name, and it would not decode. Keep
                    // the pre-existing behaviour — send it as declared — rather
                    // than silently drop an image just because the sniffer does
                    // not know its header. Only a KNOWN-unaccepted format gets
                    // the placeholder: for those the 400 is certain.
                    AppLogger.warning(
                        TAG,
                        "[ImageFormat] unrecognized ${input.size}B (declared ${declaredMime ?: "none"}) " +
                            "did not decode — sending as declared",
                    )
                    val sized = budget(input)
                    val mime = sniffFormat(sized).mimeType ?: declaredMime ?: ImageFormat.JPEG.mimeType!!
                    return UploadImage(sized, mime)
                }
                AppLogger.warning(
                    TAG,
                    "[ImageFormat] $sourceFormat (declared ${declaredMime ?: "none"}, ${input.size}B) " +
                        "could not be decoded — sending a text placeholder instead",
                )
                return null
            }
        }
        // [T-android-image-downscale-parity] Backstop for any path that did not
        // already fit the image (tool results, images added outside the composer):
        // an accepted format can still be rejected for its size — DeepSeek 400s a
        // 1264×23545 JPEG as "unsupported image".
        val fitted = fit(accepted)?.also {
            AppLogger.info(TAG, "[ImageFormat] fitted within ${MAX_EDGE_PX}px: ${accepted.size}B → ${it.size}B")
        } ?: accepted
        val sized = budget(fitted)
        val finalFormat = sniffFormat(sized)
        // The budget ladder only ever emits JPEG or returns its input, so this
        // is always uploadable; guard anyway rather than send a wrong label.
        val mime = finalFormat.mimeType ?: return null
        if (sourceFormat != finalFormat || !sameMime(declaredMime, mime)) {
            AppLogger.info(
                TAG,
                "[ImageFormat] declared=${declaredMime ?: "none"} source=$sourceFormat " +
                    "${input.size}B → sending $mime ${sized.size}B",
            )
        }
        return UploadImage(sized, mime)
    }

    private fun sameMime(declared: String?, actual: String): Boolean {
        val d = declared?.trim()?.lowercase() ?: return false
        return d == actual || (actual == "image/jpeg" && d == "image/jpg")
    }

    /**
     * Decode an unaccepted format (HEIC/HEIF, AVIF, BMP, …) and re-encode it as
     * JPEG at the composer's resolution. Null when decoding fails —
     * [compressBytes] signals that by returning its input unchanged.
     */
    private fun transcodeToJpeg(input: ByteArray): ByteArray? {
        val out = compressBytes(input, MAX_EDGE_PX, JPEG_QUALITY)
        return out.takeIf { it !== input && sniffFormat(it) == ImageFormat.JPEG }
    }

    /**
     * History images are the same ByteArray instance on every turn, and each
     * turn rebuilds the whole request — without this a single HEIC in history
     * would be decoded again on every send. Arrays hash by identity, so a
     * WeakHashMap keyed by the source array drops entries with the history.
     */
    private val transcodeCache = java.util.WeakHashMap<ByteArray, ByteArray>()

    /** Identity-keyed like [transcodeCache]; NO_FIT marks "measured, already fits". */
    private val fitCache = java.util.WeakHashMap<ByteArray, Any>()
    private val NO_FIT = Any()

    private fun fitCached(input: ByteArray): ByteArray? {
        when (val hit = synchronized(fitCache) { fitCache[input] }) {
            NO_FIT -> return null
            is ByteArray -> return hit
        }
        val out = downscaleForModel(input)?.bytes
        synchronized(fitCache) { fitCache[input] = out ?: NO_FIT }
        return out
    }

    private fun transcodeCached(input: ByteArray): ByteArray? {
        synchronized(transcodeCache) { transcodeCache[input] }?.let { return it }
        val out = transcodeToJpeg(input) ?: return null
        synchronized(transcodeCache) { transcodeCache[input] = out }
        return out
    }

    /**
     * Try increasingly aggressive (maxEdge, quality) candidates until the
     * re-encoded JPEG fits under [targetMaxBytes]. Returns the smallest
     * encoding produced when no candidate fits — never returns null because
     * "send something" beats "fail the request".
     *
     * Re-encodes from the original bytes on each candidate so the JPEG never
     * compounds artifacts.
     *
     * [T-image-budget-base64] [targetMaxBytes] is RAW bytes — this ladder
     * measures the JPEG it just produced, which has not been base64'd yet.
     * The default is therefore [MAX_PER_IMAGE_RAW_BYTES], the raw size that
     * encodes to [MAX_PER_IMAGE_BYTES]. Passing the base64 cap here would
     * compress to 5 MB raw and put ~6.7 MB on the wire — the exact overshoot
     * this change exists to remove, and the shape the provider-boundary
     * callers (Anthropic / OpenAI / Gemini) rely on by taking the default.
     */
    fun compressUnderBudget(input: ByteArray, targetMaxBytes: Long = MAX_PER_IMAGE_RAW_BYTES): ByteArray {
        if (input.size <= targetMaxBytes) return input
        var best: ByteArray = input
        var bestSize = input.size
        for ((edge, q) in LADDER) {
            val candidate = compressBytes(input, edge, q)
            if (candidate.size < bestSize) {
                best = candidate
                bestSize = candidate.size
            }
            if (candidate.size.toLong() <= targetMaxBytes) {
                AppLogger.info(TAG, "compressUnderBudget hit: ${input.size}B → ${candidate.size}B (edge=$edge q=$q)")
                return candidate
            }
        }
        AppLogger.warning(TAG, "compressUnderBudget exhausted ladder: ${input.size}B → ${best.size}B (target=${targetMaxBytes}B)")
        return best
    }

    /** Result of [applyMessageBudget]. */
    data class BudgetResult(
        /** Image bytes ready to send, in original order, dropped tail removed. */
        val keptBytes: List<ByteArray>,
        /** Number of parts whose bytes were re-encoded by the ladder. */
        val compressedCount: Int,
        /** Number of tail parts dropped because the cumulative cap was hit. */
        val droppedCount: Int,
        /** Final total payload bytes after compression + drop. */
        val totalBytes: Long,
    ) {
        val mutated: Boolean get() = compressedCount > 0 || droppedCount > 0
    }

    /**
     * Walk [bytesIn] and produce a budgeted output:
     *  - Each oversize part is run through [compressUnderBudget] first.
     *  - Then cumulative bytes are summed; once the running total would
     *    exceed [MAX_TOTAL_BYTES] the remaining tail is dropped.
     */
    fun applyMessageBudget(bytesIn: List<ByteArray>): BudgetResult {
        if (bytesIn.isEmpty()) return BudgetResult(emptyList(), 0, 0, 0L)
        val kept = ArrayList<ByteArray>(bytesIn.size)
        var compressed = 0
        var dropped = 0
        var running = 0L
        for (part in bytesIn) {
            // [T-image-budget-base64] Compare what the wire will carry, not
            // what the decoder holds. `compressUnderBudget` is given the raw
            // equivalent of the cap because its ladder measures raw JPEG.
            val sized = if (estimatedBase64Length(part.size) > MAX_PER_IMAGE_BYTES) {
                val c = compressUnderBudget(part, MAX_PER_IMAGE_RAW_BYTES)
                if (c.size != part.size) compressed += 1
                c
            } else part
            val sizedOnWire = estimatedBase64Length(sized.size)
            if (running + sizedOnWire > MAX_TOTAL_BYTES) {
                dropped += 1
                continue
            }
            kept.add(sized)
            running += sizedOnWire
        }
        if (dropped > 0 || compressed > 0) {
            AppLogger.info(
                TAG,
                "applyMessageBudget: in=${bytesIn.size} kept=${kept.size} compressed=$compressed dropped=$dropped total=${running}B",
            )
        }
        return BudgetResult(kept, compressed, dropped, running)
    }

    // ─── Request-level budget ──────────────────────────────────────────────

    /**
     * Identifies a single image part inside an outgoing request payload.
     * Used by providers to look up whether a part has been marked for
     * elision by [planRequestBudget]. The identity hash of the ByteArray
     * is sufficient — same bytes only ever appear once per request, and
     * different bytes hash uniquely enough that the worst-case collision
     * is "we keep one extra image past the budget" (safe).
     */
    @JvmInline
    value class ImagePartId(val identityHash: Int) {
        companion object {
            fun of(data: ByteArray): ImagePartId = ImagePartId(System.identityHashCode(data))
        }
    }

    /**
     * Image part scoped for budget planning. Lets the planner stay generic
     * over `LLMMessage.ImagePart` (current-turn user attachments) and
     * `AgentContentPart.ImageData` / `AgentContentPart.ToolResult.imageData`
     * (history image bytes) without depending on either type directly.
     */
    data class BudgetImage(
        val data: ByteArray,
        val linuxPath: String?,
        val mimeType: String,
    )

    /**
     * Result of [planRequestBudget].
     */
    data class RequestBudgetPlan(
        /** Identity hashes of image bytes that providers must NOT inline. */
        val droppedIds: Set<ImagePartId>,
        /** Map from dropped identity → original linux path (null if none). */
        val droppedPaths: Map<ImagePartId, String?>,
        /** Total bytes kept after planning (sum of per-image cap-clamped). */
        val keptBytes: Long,
        /** Total bytes elided. */
        val elidedBytes: Long,
        /** Number of images elided. */
        val droppedCount: Int,
        /** Total images considered (kept + dropped). */
        val totalCount: Int,
    ) {
        val mutated: Boolean get() = droppedCount > 0
    }

    /**
     * Walk all candidate images in reverse chronological order (latest
     * first) and decide which can fit under [maxBytes]. Anything beyond
     * the cap is recorded in [RequestBudgetPlan.droppedIds] so providers
     * emit a text placeholder instead of base64 bytes.
     *
     * @param images Ordered eldest → latest. The planner reverses
     *   internally so the latest user input + most recent tool results
     *   are protected from elision.
     */
    fun planRequestBudget(
        images: List<BudgetImage>,
        maxBytes: Long = MAX_REQUEST_BYTES,
    ): RequestBudgetPlan {
        if (images.isEmpty()) {
            return RequestBudgetPlan(emptySet(), emptyMap(), 0L, 0L, 0, 0)
        }
        val dropped = HashSet<ImagePartId>()
        val droppedPaths = HashMap<ImagePartId, String?>()
        var kept = 0L
        var elided = 0L
        // Walk latest → eldest so most-recent images win the budget.
        for (img in images.asReversed()) {
            val id = ImagePartId.of(img.data)
            // Per-image cap-clamped size, measured as base64 because that is
            // what [maxBytes] budgets. [T-image-budget-base64] The clamp is
            // applied after the conversion so it lands on the same ceiling a
            // provider-boundary `compressUnderBudget` would have produced.
            val effectiveSize = minOf(estimatedBase64Length(img.data.size), MAX_PER_IMAGE_BYTES)
            if (kept + effectiveSize <= maxBytes) {
                kept += effectiveSize
            } else {
                dropped.add(id)
                droppedPaths[id] = img.linuxPath
                elided += effectiveSize
            }
        }
        if (dropped.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "planRequestBudget: in=${images.size} kept=${images.size - dropped.size} dropped=${dropped.size} keptBytes=${kept}B elidedBytes=${elided}B cap=${maxBytes}B",
            )
        }
        return RequestBudgetPlan(
            droppedIds = dropped,
            droppedPaths = droppedPaths,
            keptBytes = kept,
            elidedBytes = elided,
            droppedCount = dropped.size,
            totalCount = images.size,
        )
    }

    /**
     * Build the text placeholder a provider emits in place of an elided
     * image. The model gets a clear, actionable hint: this image was
     * dropped to fit the budget, and (if known) the linux path where the
     * bytes are still readable via [read_image]. Without a path the model
     * just sees that an image was elided and can ask the user to re-attach.
     */
    fun elidedImagePlaceholder(linuxPath: String?): String {
        return if (linuxPath != null) {
            "[image elided to fit 25MB request budget. Original at $linuxPath — re-fetch with `read_image $linuxPath` if you need to see it.]"
        } else {
            "[image elided to fit 25MB request budget. Original bytes no longer addressable; ask the user to re-attach if needed.]"
        }
    }

    /**
     * Lazily persist [data] to a session-scoped spillover dir under
     * `attachments/spillover/<sha1>.<ext>` so an elided image without a
     * pre-existing linux path can still be referenced from the text
     * placeholder. The spillover dir is bind-mounted to
     * `/var/minis/attachments/spillover/` inside iSH (same mount as
     * `attachments/uploads/`). Returns the iSH-visible linux path on
     * success, or null if the write failed (in which case the placeholder
     * falls back to the no-path variant).
     *
     * Idempotent: if the same bytes already exist on disk under the
     * sha1 prefix, returns the existing path without re-writing.
     */
    fun ensureSpillover(
        sessionAttachmentsDir: java.io.File,
        data: ByteArray,
        mimeType: String,
    ): String? {
        if (data.isEmpty()) return null
        val ext = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "bin"
        }
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(data)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // SHA-1 is required by the platform; fallback is just an
            // identity hash — collisions are tolerable here.
            System.identityHashCode(data).toString(16)
        }
        val spilloverDir = java.io.File(sessionAttachmentsDir, "spillover")
        if (!spilloverDir.exists()) {
            try {
                spilloverDir.mkdirs()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover mkdirs failed: ${e.message}")
                return null
            }
        }
        val file = java.io.File(spilloverDir, "$sha.$ext")
        if (!file.exists()) {
            try {
                file.writeBytes(data)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover write failed: ${e.message}")
                return null
            }
        }
        // Mirrors uploads mount (see ChatViewModel.prepareUserAttachments).
        return "/var/minis/attachments/spillover/$sha.$ext"
    }
}
