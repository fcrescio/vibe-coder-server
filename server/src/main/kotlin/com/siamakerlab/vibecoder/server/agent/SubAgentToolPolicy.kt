package com.siamakerlab.vibecoder.server.agent

/**
 * Server-enforced tool policy for specialized sub-agents.
 *
 * These limits are intentionally enforced below the prompt layer. Local models may ignore
 * natural-language tool instructions, but they cannot bypass denied ACP client requests.
 */
data class SubAgentToolPolicy(
    val allowTerminal: Boolean = true,
    val allowFsRead: Boolean = true,
    val allowFsWrite: Boolean = true,
    val allowDevice: Boolean = true,
    val extraInstructions: String = "",
) {
    companion object {
        fun forAgent(agentName: String): SubAgentToolPolicy = when (agentName) {
            "phone-ui-navigator" -> SubAgentToolPolicy(
                allowTerminal = false,
                allowFsRead = true,
                allowFsWrite = false,
                allowDevice = true,
                extraInstructions = """
                    Server-enforced tool policy:
                    - Raw terminal/shell/ADB is disabled for this agent.
                    - Use only device_launch_app, device_screencap, device_analyze_screenshot, device_tap, and device_swipe for phone navigation.
                    - Use device_launch_app to open the assigned app package. Do not launch apps by shell command.
                    - Do not edit files.
                    - Do not act on apps outside the package assigned in the prompt.
                    - If the wrong app is visible, stop and return a blocked trace instead of force-stopping, uninstalling, or manipulating other apps.
                """.trimIndent(),
            )
            "phone-ui-run-summarizer" -> SubAgentToolPolicy(
                allowTerminal = false,
                allowFsRead = false,
                allowFsWrite = false,
                allowDevice = false,
                extraInstructions = """
                    Server-enforced tool policy:
                    - Terminal, filesystem, and device tools are disabled for this agent.
                    - Summarize only the dialogue/trace text provided in the prompt.
                    - Do not try to operate the device or inspect the project.
                """.trimIndent(),
            )
            else -> SubAgentToolPolicy()
        }
    }
}
