package com.openminis.app.tools

import android.content.Context

/**
 * [T-tools-granular-switches] Per-tool switches for the agent's optional
 * capabilities, surfaced as Settings › Agent Runtime › Agent Tools.
 *
 * Port of iOS `AgentToolSwitch` (Views/Settings/ToolsSettingsView.swift). The
 * key strings are deliberately byte-identical to iOS so a future settings sync
 * carries a user's choice across platforms unchanged.
 *
 * The gate lives HERE, at the read, rather than only in the UI: the tool
 * schema builder, the loop's dispatcher and the CLI offload bridges all call
 * [isToolEnabled], so a stored `true` cannot re-enable a capability through a
 * path that skipped the settings screen.
 */
enum class AgentToolSwitch(val key: String, val defaultValue: Boolean) {
    /** browser_use — mature, on by default. */
    BROWSER("agent.tools.browser.enabled", true),

    /** delegate_task + agent_status — still maturing, but on by default on
     *  Android because delegation already shipped here under its own switch
     *  (see [migrateLegacyIfNeeded]); flipping the default would silently
     *  disable a feature existing users have. */
    AGENTS("agent.tools.agents.enabled", true),
    ;

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(key, defaultValue)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(key, enabled).apply()
    }

    companion object {
        const val PREFS = "agent_settings"

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The switch governing a tool name; null = always available. */
        fun governing(toolName: String): AgentToolSwitch? = when (toolName) {
            "browser_use" -> BROWSER
            // [T-sub-agents-v1] The renamed tool plus the two names
            // shipped builds wrote, so the switch still governs a
            // replayed call from an older transcript.
            "subagent_task", "delegate_task", "agent_status" -> AGENTS
            else -> null
        }

        fun isToolEnabled(context: Context, toolName: String): Boolean =
            governing(toolName)?.isEnabled(context) ?: true

        // ---------------------------------------------------------------- migration

        private const val MIGRATION_KEY = "agent.tools.granular.v1.migrated"

        /**
         * Carry the pre-split choice forward, once.
         *
         * Android shipped delegation behind `AgentSettings.KEY_ENABLED`
         * ("agent.helpers.enabled"). That key is now one of two, so a user who
         * had explicitly turned delegation OFF must not silently get it back
         * when the new switch reads its own default. Only an explicitly stored
         * value migrates — an untouched install writes nothing and takes the
         * defaults.
         *
         * browser_use had no switch before and is on by default, so there is
         * nothing to carry over for it.
         */
        fun migrateLegacyIfNeeded(context: Context) {
            val p = prefs(context)
            if (p.getBoolean(MIGRATION_KEY, false)) return
            p.edit().putBoolean(MIGRATION_KEY, true).apply()
            // Never clobber a value the user already set on the new key.
            if (p.contains(AGENTS.key)) return
            if (p.contains(com.openminis.app.agent.jobs.AgentSettings.KEY_ENABLED)) {
                val legacy = p.getBoolean(
                    com.openminis.app.agent.jobs.AgentSettings.KEY_ENABLED, true,
                )
                p.edit().putBoolean(AGENTS.key, legacy).apply()
            }
        }
    }
}
