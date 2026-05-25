package com.siamakerlab.vibecoder.server.core

import com.siamakerlab.vibecoder.server.error.ApiException
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.notExists

/**
 * Defends against path traversal and absolute-path injection.
 *
 * Every filesystem-touching service MUST funnel through [normalizeAndCheck] before
 * touching disk. Tests in PathSafetyTest verify ../, absolute paths, drive letters,
 * and control bytes are rejected.
 */
object PathSafety {

    /**
     * Resolve [raw] against [root] and return the absolute, normalized Path,
     * ensuring the result is **inside** [root].
     *
     * @throws ApiException with code "path_traversal" otherwise.
     */
    fun normalizeAndCheck(root: Path, raw: String): Path {
        // Reject control bytes (NUL, CR, LF, ESC, DEL, …). These can:
        //   - Smuggle past validators that only look at printable parts
        //   - Break native path APIs on some platforms (e.g., NUL terminator)
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) {
            throw ApiException.localized(400, "invalid_path", messageKey = "api.pathSafety.controlByte")
        }
        // Reject absolute-looking strings to avoid `Path.resolve` swallowing them.
        if (raw.startsWith('/') || raw.startsWith('\\') || hasWindowsDriveLetter(raw)) {
            throw ApiException.localized(403, "path_traversal", messageKey = "api.pathSafety.absoluteNotAllowed")
        }
        val absRoot = root.toAbsolutePath().normalize()
        val candidate = absRoot.resolve(raw).normalize().absolute()
        if (!candidate.startsWith(absRoot)) {
            throw ApiException.localized(403, "path_traversal", messageKey = "api.pathSafety.escapeWorkspace", args = listOf(raw))
        }
        return candidate
    }

    /**
     * Test whether [candidate] sits underneath [root] (both pre-normalized acceptable).
     */
    fun isInside(root: Path, candidate: Path): Boolean {
        val r = root.toAbsolutePath().normalize()
        val c = candidate.toAbsolutePath().normalize()
        return c.startsWith(r)
    }

    /**
     * Defense-in-depth check for paths read from DB rows / uploads:
     * verify [absolute] lies inside [workspaceRoot].
     */
    fun checkAbsoluteIsInsideWorkspace(workspaceRoot: Path, absolute: Path): Path {
        val r = workspaceRoot.toAbsolutePath().normalize()
        val c = absolute.toAbsolutePath().normalize()
        if (!c.startsWith(r)) {
            throw ApiException.localized(403, "path_outside_workspace",
                messageKey = "api.pathSafety.notUnderWorkspace", args = listOf(c.toString()))
        }
        if (c.notExists()) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.pathSafety.notExist", args = listOf(c.toString()))
        }
        return c
    }

    private fun hasWindowsDriveLetter(raw: String): Boolean {
        if (raw.length < 2) return false
        val c0 = raw[0]
        val c1 = raw[1]
        return c1 == ':' && (c0 in 'a'..'z' || c0 in 'A'..'Z')
    }
}
