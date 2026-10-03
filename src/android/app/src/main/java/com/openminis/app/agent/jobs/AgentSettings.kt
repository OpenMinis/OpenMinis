package com.openminis.app.agent.jobs

import android.content.Context

/**
 * [T-p2-agent-settings] Settings › Agents. One switch, on by default — the
 * zero-configuration experience is the product (design v4 §9); the page
 * exists for the user who wants to turn delegation OFF, not to configure it.
 * Mirrors iOS HelperSettingsView / AIChatViewModel.helpersEnabled: the same
 * preference key, the same default.
 */
object AgentSettings {
    const val PREFS = "agent_settings"
    const val KEY_ENABLED = "agent.helpers.enabled"

    /**
     * [T-tools-granular-switches] Delegates to
     * [com.openminis.app.tools.AgentToolSwitch.AGENTS], which is now the single
     * source of truth for "may the assistant delegate".
     *
     * Settings > Agents and Settings > Tools both present this choice, and two
     * independent keys would let them disagree: a user turning delegation off
     * on one page would still see it on, and still get the tool, from the
     * other. [KEY_ENABLED] is kept only so the one-time migration can read a
     * pre-split value.
     */
    fun isEnabled(context: Context): Boolean =
        com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        com.openminis.app.tools.AgentToolSwitch.AGENTS.setEnabled(context, enabled)
    }
}
