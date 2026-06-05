package com.siamakerlab.vibecoder.shared.dto

/**
 * v0.52.0+ — accepted values for `/api/projects/{id}/history?agent=` and
 * `/api/chat/history?agent=`.
 *
 *  - `main`     — main console turns only, excluding sub-agents.
 *  - `all`      — main console plus all sub-agents.
 *  - `@<name>` — turns for one sub-agent, for example `@frontend`, `@reviewer`.
 *
 * SSR uses both bare `?agent=` and `?agent=*`; the JSON API normalizes this
 * wire contract to `main`/`all`/`@name` (v0.64.0).
 *
 * Helper [forAgentName] safely converts sub-agent names to the `@`-prefixed form.
 */
object HistoryAgentFilter {
    const val MAIN = "main"
    const val ALL = "all"

    /** Wrap a sub-agent name as `@<name>`; keep names already starting with `@`. */
    fun forAgentName(agent: String): String =
        if (agent.startsWith("@")) agent else "@$agent"
}
