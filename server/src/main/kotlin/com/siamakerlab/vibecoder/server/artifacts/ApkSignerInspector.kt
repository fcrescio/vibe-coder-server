package com.siamakerlab.vibecoder.server.artifacts

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v0.28.0 — APK 서명 정보 검사.
 *
 * Android SDK 의 `apksigner verify --verbose --print-certs <apk>` 결과를 파싱
 * 해서 빌드 디테일 페이지에 신뢰성 있는 식별 정보 표시.
 *
 * 검출 항목:
 *   - v1 (JAR signing) / v2 (APK Signature Scheme v2) / v3 / v4 — 어떤 스킴이 적용됐는가
 *   - Signer #N: Subject DN + SHA-256 fingerprint
 *   - verified or not
 *
 * `apksigner` 는 `$ANDROID_HOME/build-tools/<latest>/apksigner` 위치. 다양한
 * 버전이 있을 수 있어 가장 최신 (semver 비교) 디렉토리 선택.
 *
 * 라이브러리/네트워크 호출 없음 — Android SDK 가 설치된 컨테이너에서만 동작.
 * SDK 미설치 시 graceful error 메시지.
 */
class ApkSignerInspector {

    data class SignerCert(
        val signerIndex: Int,
        val subjectDn: String?,
        val sha256: String?,
    )

    data class Inspection(
        val verified: Boolean,
        val schemes: List<String>,   // "v1", "v2", "v3", "v4"
        val signers: List<SignerCert>,
        val errorMessage: String?,
        val rawOutput: String?,
    )

    fun inspect(apkPath: Path): Inspection {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null }
            ?: return Inspection(false, emptyList(), emptyList(),
                "ANDROID_HOME 미설정 — apksigner 사용 불가", null)
        val apksigner = locateApksigner(sdk)
            ?: return Inspection(false, emptyList(), emptyList(),
                "apksigner 를 찾을 수 없습니다 (build-tools 미설치).", null)
        if (!Files.exists(apkPath)) {
            return Inspection(false, emptyList(), emptyList(), "APK 파일 없음: $apkPath", null)
        }

        return try {
            val cmd = listOf(apksigner, "verify", "--verbose", "--print-certs", apkPath.toString())
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return Inspection(false, emptyList(), emptyList(), "apksigner verify timeout (30s)", null)
            }
            val verified = proc.exitValue() == 0
            parse(output, verified)
        } catch (e: Throwable) {
            log.warn(e) { "apksigner verify 실패" }
            Inspection(false, emptyList(), emptyList(), e.message ?: e.javaClass.simpleName, null)
        }
    }

    private fun parse(raw: String, verifiedExit: Boolean): Inspection {
        val schemes = mutableListOf<String>()
        // apksigner 출력 예 (verbose):
        //   Verified using v1 scheme (JAR signing): true
        //   Verified using v2 scheme (APK Signature Scheme v2): true
        //   Verified using v3 scheme (APK Signature Scheme v3): false
        //   ...
        //   Signer #1 certificate DN: CN=Jangwook Lee, OU=Mobile, O=Sia Makerlab, L=Jecheon, ST=Chungbuk, C=KR
        //   Signer #1 certificate SHA-256 digest: <hex>
        val schemeRegex = Regex("Verified using (v\\d) scheme[^:]*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        for (m in schemeRegex.findAll(raw)) {
            if (m.groupValues[2].equals("true", ignoreCase = true)) schemes += m.groupValues[1]
        }

        // Signer fingerprints
        val signerBuckets = sortedMapOf<Int, MutableMap<String, String?>>()
        val signerDn = Regex("Signer #(\\d+) certificate DN:\\s*(.+)")
        val signerSha = Regex("Signer #(\\d+) certificate SHA-256 digest:\\s*([0-9a-fA-F:]+)")
        for (line in raw.lines()) {
            signerDn.matchEntire(line.trim())?.let {
                val idx = it.groupValues[1].toInt()
                signerBuckets.getOrPut(idx) { mutableMapOf() }["dn"] = it.groupValues[2]
            }
            signerSha.matchEntire(line.trim())?.let {
                val idx = it.groupValues[1].toInt()
                signerBuckets.getOrPut(idx) { mutableMapOf() }["sha"] = it.groupValues[2].replace(":", "").lowercase()
            }
        }
        val signers = signerBuckets.map { (idx, kv) ->
            SignerCert(signerIndex = idx, subjectDn = kv["dn"], sha256 = kv["sha"])
        }

        // Verified 판정 — exit 0 + 최소 한 scheme 활성. Output 에 "DOES NOT VERIFY" 있으면 무조건 false.
        val outputSaysFail = raw.contains("DOES NOT VERIFY", ignoreCase = true)
        val verified = verifiedExit && schemes.isNotEmpty() && !outputSaysFail

        return Inspection(
            verified = verified,
            schemes = schemes,
            signers = signers,
            errorMessage = if (!verified) "verify failed (자세한 내용은 raw output 참고)" else null,
            rawOutput = raw.take(4000),
        )
    }

    /**
     * `$ANDROID_HOME/build-tools/` 디렉토리 안의 가장 최신 (semver 비교) 폴더에서
     * `apksigner` 실행 파일을 찾는다. 없으면 null.
     */
    private fun locateApksigner(sdk: String): String? {
        val buildTools = Path.of(sdk, "build-tools")
        if (!Files.isDirectory(buildTools)) return null
        val candidates = Files.list(buildTools).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .toList()
                .sortedByDescending { it.fileName.toString() }  // semver string 비교로 충분
        }
        for (dir in candidates) {
            val tool = dir.resolve("apksigner")
            if (Files.isExecutable(tool)) return tool.toString()
        }
        return null
    }
}
