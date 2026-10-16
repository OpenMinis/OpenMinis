package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-multiagent-page-cleanup] Guards the two removals from the multi-agent
 * settings page:
 *
 * ④ The "工具权限模式" row showed the gate's permission mode
 *   (审批/YOYO/…) but jumped to Routes.PERMISSIONS → OffloadPermissionScreen
 *   (Shizuku/offload permissions), which never edits that mode. The real edit
 *   point is the in-chat approval UI (setSessionPermissionMode). A jump whose
 *   destination cannot change the displayed value violates the section's own
 *   footer contract ("只跳到拥有该设置的那一页，不是第二套开关"), so the row
 *   was removed. This guard fails if the misleading jump comes back.
 *
 * ⑤ The "子代理类型" section was a read-only mirror of SubAgentTypeStore
 *   (no save() caller — not a setting). dispatch_agents' tool description
 *   already enumerates the types for the model, so the mirror was removed.
 *   The store itself stays: DispatchAgentsTool and ChatViewModelSubAgentExt
 *   resolve types through find() at dispatch time.
 */
class MultiAgentPageCleanupTest {

    private fun screen(): String =
        ProductionSources.read("ui/settings/MultiAgentSettingsScreen.kt")

    private fun navigation(): String =
        ProductionSources.read("ui/navigation/AppNavigation.kt")

    @Test
    fun screenHasNoPermissionModeRowOrJump() {
        val src = screen()
        assertFalse(src.contains("onOpenPermissions"))
        assertFalse(src.contains("settings_open_permissions"))
        assertFalse(src.contains("labelZh"))
        assertFalse(src.contains("SecurityGateHolder"))
    }

    @Test
    fun navigationWiresMultiAgentWithoutPermissionsJump() {
        val nav = navigation()
        val start = nav.indexOf("MultiAgentSettingsScreen(")
        assertTrue(start >= 0)
        val window = nav.substring(start, (start + 260).coerceAtMost(nav.length))
        // The only shared-control jump left is tool limits.
        assertTrue(window, window.contains("onOpenToolLimits"))
        assertFalse(window, window.contains("onOpenPermissions"))
        // The PERMISSIONS route itself stays alive for deep links and the
        // settings root; only the misleading row is gone.
        assertTrue(nav.contains("Routes.PERMISSIONS"))
    }

    @Test
    fun screenHasNoSubAgentTypeMirrorSection() {
        val src = screen()
        assertFalse(src.contains("SubAgentTypeStore"))
        assertFalse(src.contains("ma_subagent_types"))
    }

    @Test
    fun subAgentTypeStoreRemainsAvailableToDispatch() {
        // The store is runtime machinery, not a settings mirror: the dispatch
        // path must keep resolving types through it.
        val dispatch = ProductionSources.read("tools/DispatchAgentsTool.kt")
        assertTrue(dispatch.contains("SubAgentTypeStore.find"))
        val subAgent = ProductionSources.read("ui/chat/ChatViewModelSubAgentExt.kt")
        assertTrue(subAgent.contains("SubAgentTypeStore.find"))
    }

    @Test
    fun permissionModeIsStillEditableInChat() {
        // The edit point that replaced the misleading jump: the in-chat
        // approval UI owns the mode, so removing the row lost nothing.
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("fun setSessionPermissionMode"))
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(chat.contains("setSessionPermissionMode"))
    }
}
