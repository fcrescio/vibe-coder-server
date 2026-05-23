package com.siamakerlab.vibecoder.server.emulator

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v0.19.0 — Android emulator orchestration (Phase 5, scaffolding).
 *
 * **상태**: 본 cycle 은 진단 + ADB 통합 + 수동 launch 가이드. 풀 자동화
 * (compose KVM passthrough + AVD 자동 생성 + noVNC 미러링) 는 v0.20+ scope.
 *
 * 진단 항목:
 *  - /dev/kvm 존재 + readable → KVM hardware accel 가능 여부
 *  - emulator binary 존재 (Android SDK 의 emulator/)
 *  - adb binary 존재 (platform-tools)
 *  - 설치된 AVD 목록 (avdmanager list avd)
 *  - 실행 중인 device 목록 (adb devices)
 *
 * 운영 정책:
 *  - KVM 없으면 software emulation (느림, 일반 안내).
 *  - 본 컨테이너는 기본 `privileged: false` — KVM passthrough 사용하려면
 *    compose 에 `devices: [/dev/kvm:/dev/kvm]` 와 group 설정 필요.
 *  - 실 자동 launch + noVNC mirror 는 base image 부피 (qemu/x11/websockify 추가)
 *    상승 때문에 별도 image variant (`siamakerlab/vibe-coder-server:full`) 로 분리 예정.
 */
class EmulatorService {

    data class Diagnostics(
        val kvmAvailable: Boolean,
        val kvmPath: String?,
        val emulatorBinary: String?,
        val adbBinary: String?,
        val avds: List<String>,
        val runningDevices: List<String>,
        val recommendation: String,
    )

    fun diagnose(): Diagnostics {
        val kvm = Path.of("/dev/kvm")
        val kvmOk = Files.exists(kvm) && Files.isReadable(kvm) && Files.isWritable(kvm)
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null }
        val emulator = sdk?.let { "$it/emulator/emulator" }?.takeIf { Path.of(it).let(Files::exists) }
        val adb = sdk?.let { "$it/platform-tools/adb" }?.takeIf { Path.of(it).let(Files::exists) }
        val avds = if (sdk != null) runAvdmanagerList(sdk) else emptyList()
        val devices = if (adb != null) runAdbDevices(adb) else emptyList()
        val rec = buildRecommendation(kvmOk, sdk, emulator, adb)
        return Diagnostics(
            kvmAvailable = kvmOk,
            kvmPath = if (kvmOk) "/dev/kvm" else null,
            emulatorBinary = emulator,
            adbBinary = adb,
            avds = avds,
            runningDevices = devices,
            recommendation = rec,
        )
    }

    private fun buildRecommendation(kvm: Boolean, sdk: String?, emu: String?, adb: String?): String =
        buildString {
            if (sdk == null) {
                appendLine("- Android SDK 가 설치되지 않았습니다. /env-setup 페이지에서 Android SDK 설치.")
            } else if (emu == null) {
                appendLine("- `emulator` 패키지가 없습니다. `sdkmanager 'emulator'` 로 설치.")
            }
            if (adb == null) {
                appendLine("- ADB (`platform-tools`) 가 없습니다. SDK 설치 시 자동 포함됨.")
            }
            if (!kvm) {
                appendLine("- /dev/kvm 사용 불가. compose 의 vibe-coder-server 서비스에 다음 추가:")
                appendLine("    ```yaml")
                appendLine("    devices:")
                appendLine("      - /dev/kvm:/dev/kvm")
                appendLine("    ```")
                appendLine("  + 호스트의 kvm 그룹에 운영자 추가 (Linux 의 경우 `sudo usermod -aG kvm \$USER`).")
                appendLine("  KVM 없이도 software emulation 으로 실행은 되지만 매우 느림 (10× 이상).")
            }
            if (isEmpty()) {
                append("✓ KVM + SDK + emulator + adb 모두 준비. AVD 를 생성 (`avdmanager create avd`) 후 launch 가능.")
            }
        }

    /**
     * 실행 중인 emulator 에 APK 설치 (`adb -s <device> install -r <apk>`).
     * 본 cycle 은 BuildService 의 APK 위치를 받아 동작 — 자동 emulator 실행 후 install
     * 까지는 미구현 (수동으로 emulator 띄운 상태에서만 동작).
     */
    fun installApk(deviceSerial: String, apkPath: Path): InstallResult {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: return InstallResult(false, "ANDROID_HOME unset")
        val adb = "$sdk/platform-tools/adb"
        if (!Files.exists(Path.of(adb))) return InstallResult(false, "adb not found at $adb")
        if (!Files.exists(apkPath)) return InstallResult(false, "apk not found: $apkPath")
        val cmd = listOf(adb, "-s", deviceSerial, "install", "-r", apkPath.toString())
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                InstallResult(false, "adb install timeout (60s)")
            } else {
                InstallResult(proc.exitValue() == 0, output)
            }
        } catch (e: Throwable) {
            InstallResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    data class InstallResult(val ok: Boolean, val log: String)

    private fun runAvdmanagerList(sdk: String): List<String> {
        val tool = "$sdk/cmdline-tools/latest/bin/avdmanager"
        if (!Files.exists(Path.of(tool))) return emptyList()
        return try {
            val pb = ProcessBuilder(tool, "list", "avd", "-c").redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                emptyList()
            } else if (proc.exitValue() != 0) {
                emptyList()
            } else {
                out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
            }
        } catch (e: Throwable) {
            log.debug(e) { "avdmanager list failed" }
            emptyList()
        }
    }

    private fun runAdbDevices(adb: String): List<String> {
        return try {
            val pb = ProcessBuilder(adb, "devices").redirectErrorStream(false)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                emptyList()
            } else {
                // adb devices output: header + "<serial>\t<state>" lines
                out.lines()
                    .drop(1)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.contains("\t") }
                    .map { it.substringBefore("\t") }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
