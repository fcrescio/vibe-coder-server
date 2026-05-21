package com.siamakerlab.vibecoder.server.actions

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.ActionTreeDto
import com.siamakerlab.vibecoder.shared.dto.CapabilityKey
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * Loads action manifests from two sources:
 *  - Bundled JSON files under classpath `/actions/` — system defaults.
 *  - Workspace user file (`<workspace>/.vibecoder/actions.user.json`) — per-server customization.
 *    Phase E adds mtime-based hot reload.
 *
 * The two sources are merged: user categories with the same `id` override system ones,
 * user-only categories are appended at the end, and within a category, user actions
 * override system actions by `id`.
 */
class ProjectActionRegistry(
    private val workspace: WorkspacePath,
    /** Classpath resource directory containing system manifests. */
    private val resourceDir: String = "/actions",
    /** Cache invalidation period for the workspace user file. */
    private val pollIntervalMs: Long = 10_000L,
) {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private data class Cache(
        val tree: ActionTreeDto,
        val userMtime: Long?,
        val loadedAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Cache>()

    /**
     * Build the merged tree for [projectId] (cached, refreshed when user file mtime changes).
     *
     * Callers that need the live capability map should pass it in via [capabilities] —
     * we merge it into the returned [ActionTreeDto] without invalidating the cache (the
     * action *shape* depends on disk files, not on whether `claude` is currently up).
     */
    fun listForProject(
        projectId: String,
        capabilities: Map<String, Boolean> = emptyMap(),
    ): ActionTreeDto {
        val userFile = userManifestFile()
        val mcpFile = mcpFile(projectId)
        val userMtime = mtime(userFile)
        val mcpMtime = mtime(mcpFile)
        val compositeMtime = (userMtime ?: 0L) xor ((mcpMtime ?: 0L) shl 1)
        val now = System.currentTimeMillis()

        val cached = cache[projectId]
        val baseTree = if (cached != null
            && cached.userMtime == compositeMtime
            && now - cached.loadedAtMs < pollIntervalMs) {
            cached.tree
        } else {
            val systemCats = loadSystemCategories()
            val userCats = loadUserCategories(userFile)
            val mcpCats = loadMcpCategories(mcpFile)
            val merged = mergeCategories(systemCats + mcpCats, userCats)
            val fresh = ActionTreeDto(categories = merged.map { it.toDto() })
            cache[projectId] = Cache(fresh, compositeMtime, now)
            fresh
        }
        return if (capabilities.isEmpty()) baseTree else baseTree.copy(capabilities = capabilities)
    }

    /**
     * MCP servers declared in this project's `.mcp.json`. Used by the capability
     * map to mark `mcp:<server>` as available.
     */
    fun mcpServerNames(projectId: String): List<String> {
        val file = mcpFile(projectId)
        if (!file.exists()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }
            .getOrNull() ?: return emptyList()
        val servers = root["mcpServers"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return emptyList()
        return servers.keys.toList()
    }

    private fun mtime(p: Path): Long? =
        if (p.exists()) runCatching { p.getLastModifiedTime().toMillis() }.getOrNull() else null

    fun invalidate(projectId: String? = null) {
        if (projectId == null) cache.clear() else cache.remove(projectId)
    }

    /** Resolve a single action by id within a project's merged tree. */
    fun findAction(projectId: String, actionId: String): ProjectAction? {
        val systemCats = loadSystemCategories()
        val userCats = loadUserCategories(userManifestFile())
        val mcpCats = loadMcpCategories(mcpFile(projectId))
        return mergeCategories(systemCats + mcpCats, userCats)
            .flatMap { it.actions }
            .firstOrNull { it.id == actionId }
    }

    /**
     * Auto-discover an MCP category from a project's `.mcp.json`.
     *
     * Standard MCP entry shape (per Claude Code docs):
     *   { "mcpServers": { "name": { "command": "...", "args": [...] } } }
     *
     * vibe-coder extension: if the entry also contains a `tools` array, each item
     * becomes its own per-tool chip (lighter than spawning the MCP server just to
     * enumerate). This gives users a way to pin frequently-used tools without code
     * changes — write the names once in `.mcp.json`, get one chip per tool.
     *
     *   "bkit": {
     *     "command": "...",
     *     "args": [...],
     *     "tools": [
     *       { "name": "bkit_pdca_status", "label": "PDCA Status", "icon": "Activity" },
     *       { "name": "bkit_pdca_history" }                  // label defaults to name
     *     ]
     *   }
     *
     * When `tools` is missing/empty, we fall back to a single per-server "open"
     * chip so the user can at least see that the server is registered.
     */
    private fun loadMcpCategories(file: Path): List<ActionCategory> {
        if (!file.exists()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }
            .onFailure { log.warn(it) { "failed to parse .mcp.json at $file" } }
            .getOrNull() ?: return emptyList()
        val servers = root["mcpServers"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return emptyList()
        if (servers.isEmpty()) return emptyList()

        val actions = mutableListOf<ProjectAction>()
        for ((serverName, raw) in servers.entries) {
            val cap = CapabilityKey.mcp(serverName)
            val obj = runCatching { raw.jsonObject }.getOrNull()
            val tools = obj?.get("tools")?.let { runCatching { it.jsonArray }.getOrNull() }
            if (tools != null && tools.isNotEmpty()) {
                tools.forEachIndexed { idx, toolElement ->
                    val toolObj = runCatching { toolElement.jsonObject }.getOrNull()
                    if (toolObj == null) {
                        log.warn { "[$file] mcpServers.$serverName.tools[$idx] is not an object; skipped" }
                        return@forEachIndexed
                    }
                    val toolName = toolObj["name"]?.jsonPrimitive?.contentOrNull
                    if (toolName.isNullOrBlank()) {
                        log.warn { "[$file] mcpServers.$serverName.tools[$idx] missing 'name'; skipped" }
                        return@forEachIndexed
                    }
                    actions += ProjectAction.InvokeMcpTool(
                        id = "mcp:$serverName:$toolName",
                        label = toolObj["label"]?.jsonPrimitive?.contentOrNull ?: toolName,
                        icon = toolObj["icon"]?.jsonPrimitive?.contentOrNull ?: "Plug",
                        requires = listOf(cap),
                        mcpServer = serverName,
                        toolName = toolName,
                        argsTemplate = toolObj["argsTemplate"],
                    )
                }
            } else {
                actions += ProjectAction.InvokeMcpTool(
                    id = "mcp:$serverName",
                    label = serverName,
                    icon = "Plug",
                    requires = listOf(cap),
                    mcpServer = serverName,
                    toolName = "*",
                    argsTemplate = null,
                )
            }
        }
        if (actions.isEmpty()) return emptyList()
        return listOf(
            ActionCategory(
                id = "mcp",
                label = "MCP",
                icon = "Network",
                actions = actions,
            ),
        )
    }

    private fun loadSystemCategories(): List<ActionCategory> {
        val names = SYSTEM_MANIFESTS
        val out = mutableListOf<ActionCategory>()
        for (name in names) {
            val path = "$resourceDir/$name"
            val text = javaClass.getResource(path)?.readText()
            if (text == null) {
                log.debug { "missing system manifest $path (skipped)" }
                continue
            }
            runCatching { json.decodeFromString(ActionManifest.serializer(), text) }
                .onSuccess { out += it.categories }
                .onFailure { log.warn(it) { "failed to parse $path" } }
        }
        return out
    }

    private fun loadUserCategories(file: Path): List<ActionCategory> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ActionManifest.serializer(), file.readText())
        }.onFailure { log.warn(it) { "failed to parse user manifest $file" } }
            .getOrNull()?.categories ?: emptyList()
    }

    private fun mergeCategories(
        system: List<ActionCategory>,
        user: List<ActionCategory>,
    ): List<ActionCategory> {
        val systemById = system.associateBy { it.id }
        val userById = user.associateBy { it.id }

        val merged = LinkedHashMap<String, ActionCategory>()
        for (cat in system) {
            val u = userById[cat.id]
            merged[cat.id] = if (u == null) cat else mergeOne(cat, u)
        }
        for (cat in user) {
            if (cat.id !in systemById) merged[cat.id] = cat
        }
        return merged.values.toList()
    }

    private fun mergeOne(base: ActionCategory, override: ActionCategory): ActionCategory {
        val byId = base.actions.associateBy { it.id }.toMutableMap()
        for (act in override.actions) byId[act.id] = act
        return base.copy(
            label = override.label.ifBlank { base.label },
            icon = override.icon ?: base.icon,
            actions = byId.values.toList(),
        )
    }

    private fun userManifestFile(): Path = workspace.root.resolve(".vibecoder").resolve("actions.user.json")

    private fun mcpFile(projectId: String): Path = workspace.projectRoot(projectId).resolve(".mcp.json")

    companion object {
        private val SYSTEM_MANIFESTS = listOf("build.json", "git.json", "claude.json", "snippets.json")
    }
}
