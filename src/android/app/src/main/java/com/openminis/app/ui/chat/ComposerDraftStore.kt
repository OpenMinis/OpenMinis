package com.openminis.app.ui.chat

import android.content.Context

/**
 * [T-android-composer-draft-restore] One-shot restore of the text a user left
 * unsent in a new chat.
 *
 * Upstream: "A new chat you left with unsent text brings that text back the
 * next time you start a new chat, for up to 15 minutes." Android had no such
 * carry-over, so backing out of a draft (to check a file, or by accident) threw
 * the message away and the user had to retype it.
 *
 * Deliberately scoped to DRAFTS (`__new__` sessions): an existing chat's
 * composer is expected to come back empty, and restoring text into a live
 * conversation would read as a ghost message. One-shot too — the value is
 * cleared the moment it is read, so the draft can't resurface in a chat the
 * user has since started typing in.
 *
 * SharedPreferences, not the DB: this is ephemeral UI state with a 15-minute
 * lifetime, and it must survive process death to be useful at all.
 */
internal object ComposerDraftStore {
    private const val PREFS = "minis_composer_draft"
    private const val KEY_TEXT = "text"
    private const val KEY_SAVED_AT = "savedAt"

    /** Matches upstream's restore window. */
    const val MAX_AGE_MS = 15 * 60 * 1000L

    fun save(context: Context, text: String) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (text.isBlank()) {
            // Nothing worth keeping — and stale text must not survive a clear.
            prefs.edit().remove(KEY_TEXT).remove(KEY_SAVED_AT).apply()
            return
        }
        prefs.edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * The saved draft when it is still inside [MAX_AGE_MS], else null. Reading
     * consumes it (one-shot); expired text is dropped on the way out.
     */
    fun takeIfFresh(context: Context, nowMs: Long = System.currentTimeMillis()): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_TEXT, null)
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val fresh = text != null && text.isNotBlank() && savedAt > 0L &&
            nowMs - savedAt <= MAX_AGE_MS
        prefs.edit().remove(KEY_TEXT).remove(KEY_SAVED_AT).apply()
        return if (fresh) text else null
    }
}

/**
 * Restore a fresh draft into a BRAND-NEW chat's composer. No-op for existing
 * chats — see [ComposerDraftStore] for why the scope is drafts only.
 */
internal fun ChatViewModel.restoreComposerDraftIfAny() {
    if (!isDraft) return
    ComposerDraftStore.takeIfFresh(context)?.let { _inputText.value = it }
}

/** Keep this draft's unsent text for the next New chat (15-minute window). */
internal fun ChatViewModel.saveComposerDraftIfAny() {
    if (!isDraft) return
    ComposerDraftStore.save(context, _inputText.value)
}
