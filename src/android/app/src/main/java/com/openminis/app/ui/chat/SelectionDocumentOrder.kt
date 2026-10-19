package com.openminis.app.ui.chat

/**
 * [T-android-selection-offscreen-order] (GH#296) Where a text shard sits in the
 * document, independent of whether it is composed right now.
 *
 * A selection that grows past one screen scrolls its start out of the
 * LazyColumn (the handle drag auto-scrolls at the edge). The start shard then
 * unregisters, and everything that ordered shards by their on-screen y — which
 * endpoint comes first, which shards lie between them, which text to copy —
 * lost its footing: the highlight vanished from every paragraph between the
 * endpoints, and a copy returned only the tail of the last shard.
 *
 * The chat already had a document-order key (`shardOrderKey`, 0224dacb1), but
 * it parsed the number after the last ':' of the shard id, and 958ff9290 then
 * suffixed every MdText id with `#<subIndex>` — "3#0" is not an int, so the key
 * was null for every shard in the chat and ordering silently fell back to y.
 * It was also only an index within one parent block, so it could not order
 * blocks of different parents, nor different messages.
 *
 * [DocOrder] is (row in the flattened chat list, sub-index within that row),
 * which orders any two shards of the chat, across blocks and messages.
 */
data class DocOrder(val row: Int, val sub: Int) : Comparable<DocOrder> {
    override fun compareTo(other: DocOrder): Int =
        compareValuesBy(this, other, DocOrder::row, DocOrder::sub)
}

/**
 * What ordering and copy need to know about a shard: its text and where it
 * was. Captured from a live [TextShard] on register, and FROZEN on unregister
 * while a selection is active (see SelectionController.retired), so a shard
 * that scrolled away still contributes its text to the copy.
 *
 * Kept separate from [TextShard] so the logic can be exercised without a
 * Compose TextLayoutResult.
 */
class ShardText(
    val id: TextShardId,
    val plainText: String,
    val rawMarkdown: String?,
    /** Current window y, or null once the shard is gone / unmeasurable. */
    val y: () -> Float?,
)

/**
 * The flattened-row key (FlatChatItem.key) a chat shard belongs to, or null
 * when the shard id has no chat row shape. Mirrors the shard ids ChatScreen
 * builds for each row kind:
 *
 *   shard "text:<blockId>[#n]"            -> row "text:<messageId>:<blockId>"
 *   shard "mdblock:<parent>:<index>[#n]"  -> row "mdblock:<messageId>:<parent>:<index>"
 *   shard "legacy[#n]"                    -> row "legacy:<messageId>"
 *
 * The `#n` is the MdText sub-index (StreamingMarkdownText's
 * ShardSubIndexAllocator) and is not part of the row key.
 */
internal fun chatRowKeyForShard(id: TextShardId): String? {
    val base = shardBaseId(id.shardId)
    return when {
        base.startsWith("text:") -> "text:${id.messageId}:${base.removePrefix("text:")}"
        base.startsWith("mdblock:") -> "mdblock:${id.messageId}:${base.removePrefix("mdblock:")}"
        base == "legacy" -> "legacy:${id.messageId}"
        else -> null
    }
}

/** The MdText sub-index suffix (`#n`) of a shard id, 0 when absent. */
internal fun shardSubIndex(shardId: String): Int {
    val hash = shardId.lastIndexOf('#')
    if (hash < 0) return 0
    return shardId.substring(hash + 1).toIntOrNull() ?: 0
}

private fun shardBaseId(shardId: String): String {
    val hash = shardId.lastIndexOf('#')
    return if (hash >= 0 && shardId.substring(hash + 1).toIntOrNull() != null) shardId.substring(0, hash) else shardId
}

/**
 * The chat's [SelectionController.documentOrder]: a shard's row position from
 * the flattened-row index ChatScreen already publishes (`flatRowIndexByKey`),
 * plus its sub-index. Null when the row is not in the loaded window.
 */
internal fun chatDocumentOrder(rowIndexByKey: () -> Map<String, Int>): (TextShardId) -> DocOrder? = { id ->
    chatRowKeyForShard(id)
        ?.let { rowIndexByKey()[it] }
        ?.let { DocOrder(it, shardSubIndex(id.shardId)) }
}
