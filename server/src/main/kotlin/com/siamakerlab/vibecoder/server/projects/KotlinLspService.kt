package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * v0.73.0 — Phase 55 #7. Kotlin LSP service (optional).
 *
 * 기본은 stub — `SymbolFinder` (v0.54.0 regex) 가 default. Kotlin LSP server
 * binary 가 PATH 또는 환경 변수 `KOTLIN_LSP_PATH` 로 찾아지면 LSP-backed lookup
 * 활성화 가능 (다음 cycle 에서 정밀화 — 본 v0.73.0 은 detection + skeleton 만).
 *
 * Why stub-only in v0.73.0:
 *  - kotlin-language-server bundled image 가 ~300MB.
 *  - JSON-RPC stdio + workspace open + textDocument/definition 통합이 큰 작업.
 *  - 사용자가 진정 LSP 정확도 (rename / references) 필요한지 검증 후 정밀화 권장.
 *
 * 활성화 방법 (사용자가 별도 install 시):
 *   1. https://github.com/Kotlin/kotlin-lsp 또는 fwcd/kotlin-language-server 빌드.
 *   2. binary 를 컨테이너에 마운트: `KOTLIN_LSP_PATH=/path/to/kotlin-language-server`
 *   3. Restart server. `isAvailable=true` 로 변경 → 후속 cycle 에서 SymbolFinder 대체.
 */
class KotlinLspService(private val workspace: WorkspacePath) {

    private val lspPath: String? = System.getenv("KOTLIN_LSP_PATH")?.ifBlank { null }

    /** LSP binary 존재 여부 + executable. */
    val isAvailable: Boolean by lazy {
        val p = lspPath ?: return@lazy false
        val path = Path.of(p)
        val ok = Files.exists(path) && Files.isExecutable(path)
        if (!ok) log.warn { "KOTLIN_LSP_PATH set but not executable: $p" }
        else log.info { "Kotlin LSP detected at $p — stub mode (정밀 통합은 후속 cycle)" }
        ok
    }

    /**
     * symbol definition lookup. v0.73.0 stub — 항상 빈 결과 + log.
     * 호출자는 SymbolFinder fallback 을 그대로 사용해야 함.
     */
    fun definition(projectId: String, symbolName: String): List<DefinitionHit> {
        if (!isAvailable) return emptyList()
        log.debug { "Kotlin LSP definition stub: project=$projectId symbol=$symbolName" }
        return emptyList()  // TODO: JSON-RPC textDocument/definition 후속 cycle
    }

    data class DefinitionHit(
        val relPath: String,
        val lineNumber: Int,
        val kind: String,
        val line: String,
    )
}
