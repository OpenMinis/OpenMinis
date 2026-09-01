package com.openminis.app.data.db

/** Raw usage record from joined messages + sessions query. */
data class UsageRecord(
    /**
     * [T-android-usage-orphan-rows] Nullable because `allUsageRecords` uses a
     * LEFT JOIN (GH#168): a message whose `sessions` row is missing still
     * carries real, already-billed token usage and must be counted, but it has
     * no session to read a model id from. Room would throw on the NULL if this
     * stayed non-null. Callers group these under "Unknown".
     */
    val modelId: String?,
    /**
     * [T-token-attribution-snapshot] Display name captured when the message was
     * written. Non-null only when [hasSnapshot]. Lets the Usage page render a
     * model whose provider the user has since deleted — for a CUSTOM model the
     * live config is the only other source, and deleting the instance drops it.
     */
    val modelDisplayName: String?,
    /** `ProviderType` rawValue captured at write time. Non-null only when [hasSnapshot]. */
    val providerType: String?,
    /**
     * True when this row carries a per-message snapshot (accurate). False for
     * rows written before the snapshot columns existed, whose model is a
     * best-effort guess from the session's CURRENT model — the UI must label
     * those as estimated rather than presenting them as fact.
     */
    val hasSnapshot: Boolean,
    val tokenUsage: String,
    val createdAt: Long,
    val sessionId: String,
)
