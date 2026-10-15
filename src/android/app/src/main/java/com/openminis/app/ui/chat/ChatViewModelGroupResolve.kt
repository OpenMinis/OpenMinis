package com.openminis.app.ui.chat

import com.openminis.app.data.CapabilityRouter
import com.openminis.app.data.ModelCapability
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker

/**
 * [T-capability-route-no-vm-mutation] Top-level pure resolution: which
 * group member serves a request with these needs. No ViewModel, no repo,
 * no side effects — directly unit-testable. The ViewModel extension
 * [resolveGroupTargetEntry] is a thin shell that feeds it live data.
 *
 * @param sessionKey loadBalance hash key (real session id when present) —
 *        kept stable per session so re-entering a chat never reshuffles.
 * @return the target entry plus the routing reason (for status reporting),
 *         or null when every member is gone.
 */
internal fun pickGroupTarget(
    group: ModelGroup,
    available: List<ModelEntry>,
    preferredEntryId: String?,
    needed: Set<ModelCapability>,
    sessionKey: String,
): Pair<ModelEntry, String?>? {
    val decision = CapabilityRouter.decide(available, needed, preferredEntryId)
    val members = decision.members
    if (members.isEmpty()) return null

    // preferredEntryId comes from a prior session binding ("user picked
    // this entry inside the group last time"). Honor it only if it is
    // still available; otherwise fall through to the strategy so the
    // session can still proceed on a now-degraded group.
    val targetEntry = members.firstOrNull { it.id == preferredEntryId }
        ?: when (group.strategy) {
            // [T-android-group-resolve-skip-uncredentialed] Honor the
            // group's routing strategy, which initial selection previously
            // ignored entirely — loadBalance silently behaved as fallback.
            // Hashing the session id keeps the choice STABLE for a given
            // session (re-entering it must not reshuffle the model) while
            // spreading distinct sessions across members, matching iOS.
            RoutingStrategy.loadBalance ->
                members[
                    Math.floorMod(
                        sessionKey.hashCode(),
                        members.size,
                    ),
                ]
            RoutingStrategy.fallback -> members.first()
        }
    return targetEntry to decision.reason
}

/**
 * [T-capability-route-no-vm-mutation] Pure resolution: which group member
 * should serve a request with these needs — WITHOUT touching any ViewModel
 * state. Callers that want the resolved member to become the session's
 * active model follow up with [applyGroupEntry] (or the combined
 * [resolveProviderFromGroup]); callers that only need a per-turn detour
 * (capability routing in ChatViewModelSendExt) consume the entry directly.
 *
 * The split exists because the old monolith unconditionally rewrote
 * `currentProvider`/`_modelName` while `pinActiveEntry = false` deliberately
 * left `_activeEntryId` on the user's pick — a one-turn detour for an
 * image-needing message then left the VM showing model A while
 * buildSystemPrompt injected the persona of entry B. Resolve and apply are
 * different decisions; forcing them through one function made the mismatch
 * inevitable.
 *
 * @return the target entry plus the routing reason (for status reporting),
 *         or null when the group or every usable member is gone.
 */
internal fun ChatViewModel.resolveGroupTargetEntry(
    groupId: String,
    preferredEntryId: String? = null,
    needed: Set<ModelCapability> = emptySet(),
): Pair<ModelEntry, String?>? {
    val group = providerRepository.group(groupId) ?: return null
    // [T-android-group-resolve-skip-uncredentialed] FILTER FIRST, THEN
    // PICK — mirroring iOS `ModelGroupRouter.resolve`.
    //
    // This used to take `enabledMemberEntries.first()` and only THEN check
    // the credential, bailing out of the whole group with `?: return false`
    // when that one member had none. So a group whose first member sat on
    // a provider without a credential resolved to nothing at all, even when
    // later members were perfectly usable — and the caller fell through to
    // the new-chat default chain, which picks by "most recently added
    // provider" and therefore landed on a model that was not in the group
    // the user had selected (field report: a group of Claude+GPT entries
    // resolving to an unrelated self-hosted model on every new chat).
    //
    // The same repo already got this right one layer down:
    // buildFallbackProviders skips an uncredentialed member with
    // `?: continue`. Selection and fallback now agree.
    //
    // Note availableMemberEntries also drops HIDDEN entries, matching iOS.
    // enabledMemberEntries (still used by the settings UI) deliberately
    // does not — "switched on" is the right question there, "usable right
    // now" is the right question here.
    return pickGroupTarget(
        group,
        providerRepository.availableMemberEntries(group),
        preferredEntryId,
        needed,
        realSessionId.ifEmpty { sessionId },
    )
}

/**
 * [T-capability-route-no-vm-mutation] Apply a resolved group entry to the
 * session's ViewModel state. `pinActiveEntry = false` keeps the user's
 * active entry (the next plain-text turn routes back to it) while still
 * swapping the live provider — used by the disabled-provider re-resolve
 * paths that must keep serving traffic.
 */
internal fun ChatViewModel.applyGroupEntry(
    targetEntry: ModelEntry,
    groupId: String,
    pinActiveEntry: Boolean = true,
): Boolean {
    val group = providerRepository.group(groupId) ?: return false
    val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
    // Non-null by construction: availableMemberEntries already required a
    // credential. An OAuth instance has no API key to pass — the factory
    // reads its token from storage — so "" is the correct argument there.
    val apiKey = providerRepository.usableApiKey(instance) ?: ""

    currentModel = targetEntry.model
    _modelName.value = targetEntry.model.displayName
    _providerName.value = instance.label.ifEmpty { targetEntry.model.provider }
    _selectedGroupName.value = group.name
    if (pinActiveEntry) {
        _activeEntryId.value = targetEntry.id
    }
    currentProvider = ProviderFactory.create(instance, apiKey, targetEntry.model, context)
    return true
}

/**
 * Resolve + apply in one step. Every caller that wants the resolved member
 * to BECOME the session's model goes through here; the routing reason is
 * surfaced to the status tracker.
 */
internal fun ChatViewModel.resolveProviderFromGroup(
    groupId: String,
    preferredEntryId: String? = null,
    needed: Set<ModelCapability> = emptySet(),
    pinActiveEntry: Boolean = true,
): Boolean {
    val resolved = resolveGroupTargetEntry(groupId, preferredEntryId, needed) ?: return false
    resolved.second?.let { SessionActivityTracker.updateToolStatus(it.take(80)) }
    return applyGroupEntry(resolved.first, groupId, pinActiveEntry)
}
