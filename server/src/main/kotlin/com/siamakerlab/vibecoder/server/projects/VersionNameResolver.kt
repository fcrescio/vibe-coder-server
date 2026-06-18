package com.siamakerlab.vibecoder.server.projects

/**
 * v1.128.7 — Static Android `versionName` resolver.
 *
 * Resolves versionName from build.gradle(.kts) without running Gradle.
 * Supports: string literals, val references, string interpolation,
 * version.properties lookups, and multi-level indirection.
 * Returns null when resolution fails (rather than showing raw variable names).
 */
internal object VersionNameResolver {

    private val VERSION_NAME = Regex("""\bversionName\s*=\s*(.+)""")
    private val VAL_DEF = Regex("""\bval\s+([A-Za-z_]\w*)\s*=\s*(.+)""")
    private val VERSION_PROPS = Regex("""versionProps\s*\[\s*"(\w+)"\s*]""")
    private val ELVIS_STR = Regex("""\?:\s*"([^"]*)"""")
    private val STR_LIT = Regex("""^"(.*)"""")
    private val INT_HEAD = Regex("""^(\d+)""")
    private val IDENT_HEAD = Regex("""^([A-Za-z_]\w*)""")
    private val INTERP_BLOCK = Regex("""\$\{([^}]*)}""")
    private val INTERP_VAR = Regex("""\$([A-Za-z_]\w*)""")

    fun resolve(gradleText: String, props: Map<String, String>): String? {
        val text = gradleText.lineSequence().joinToString("\n") { it.substringBefore("//") }
        val rhs = VERSION_NAME.findAll(text)
            .map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotBlank() } ?: return null
        val resolved = eval(rhs, text, props, 0) ?: return null
        return resolved.takeIf { it.isNotBlank() && '$' !in it }?.take(32)
    }

    private fun eval(exprRaw: String, text: String, props: Map<String, String>, depth: Int): String? {
        if (depth > 8) return null
        val e = exprRaw.trim()
        if (e.startsWith("\"")) {
            STR_LIT.find(e)?.let { return substVars(it.groupValues[1], text, props, depth) }
            return null
        }
        VERSION_PROPS.find(e)?.let { m ->
            props[m.groupValues[1]]?.let { return it }
            ELVIS_STR.find(e)?.let { return it.groupValues[1] }
            return null
        }
        INT_HEAD.find(e)?.let { return it.groupValues[1] }
        IDENT_HEAD.find(e)?.let { m ->
            VAL_DEF.findAll(text).firstOrNull { it.groupValues[1] == m.groupValues[1] }?.let {
                return eval(it.groupValues[2], text, props, depth + 1)
            }
        }
        return null
    }

    private fun substVars(s: String, text: String, props: Map<String, String>, depth: Int): String {
        if (depth > 8) return s
        var r = INTERP_BLOCK.replace(s) { m -> eval(m.groupValues[1], text, props, depth + 1) ?: m.value }
        r = INTERP_VAR.replace(r) { m -> eval(m.groupValues[1], text, props, depth + 1) ?: m.value }
        return r
    }
}
