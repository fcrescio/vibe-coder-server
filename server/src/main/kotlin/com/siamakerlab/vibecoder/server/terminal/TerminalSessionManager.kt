package com.siamakerlab.vibecoder.server.terminal

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * v1.6.0 — Workspace bash 의 PTY 한 인스턴스.
 *
 * 컨테이너 안에서만 작동 — 호스트 shell 영향 없음. workspace 디렉토리에서 시작
 * (cwd `/workspace` 기본). pty4j 가 native PTY 생성, stdout/stderr 가 [output]
 * SharedFlow 로 흘러나오고 [write] 가 stdin 으로 들어감.
 */
class TerminalSession(
    val id: String,
    val workdir: String,
    private val process: PtyProcess,
    private val scope: CoroutineScope,
) {
    val createdAt: Instant = Instant.now()

    private val _output = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 256,
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    private val _exit = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
    val exit: SharedFlow<Int> = _exit.asSharedFlow()

    private var readJob: Job? = null
    private val outStream = process.outputStream
    private val inStream = process.inputStream

    fun start() {
        readJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            try {
                while (isActive) {
                    val n = runCatching { inStream.read(buf) }.getOrNull() ?: -1
                    if (n < 0) break
                    if (n > 0) {
                        val s = String(buf, 0, n, StandardCharsets.UTF_8)
                        _output.emit(s)
                    }
                }
            } finally {
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                _exit.emit(code)
                log.info { "[term $id] exited code=$code" }
            }
        }
    }

    fun write(data: String) {
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        runCatching {
            outStream.write(bytes)
            outStream.flush()
        }.onFailure { log.debug(it) { "[term $id] write failed (process dead?)" } }
    }

    fun resize(cols: Int, rows: Int) {
        runCatching { process.winSize = WinSize(cols.coerceIn(1, 999), rows.coerceIn(1, 999)) }
            .onFailure { log.debug(it) { "[term $id] resize failed" } }
    }

    fun isAlive(): Boolean = process.isAlive

    /** Graceful — first SIGTERM, then destroyForcibly after 2s if still alive. */
    fun kill() {
        runCatching {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        readJob?.cancel()
    }
}

/**
 * v1.6.0 — 전체 활성 terminal session 의 in-memory 등록부.
 *
 *  - [create]: 신규 PTY spawn. cwd 기본 `/workspace` (호스트 vibe-coder-data
 *    /workspace 마운트 위치 — 컨테이너 안에서 그대로 read/write 가능).
 *  - [get] / [list] / [close]: 호출 기본.
 *  - 30분 idle (read 없음) timeout 자동 종료 — TODO (현재는 사용자 explicit close).
 */
class TerminalSessionManager(
    private val workspaceRoot: String = "/workspace",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    fun create(workdir: String = workspaceRoot, cols: Int = 80, rows: Int = 24): TerminalSession {
        val id = UUID.randomUUID().toString().take(12)
        // 컨테이너 내부 bash. interactive + login → ~/.bashrc 로드되어 PATH /
        // alias 정상. TERM=xterm-256color 로 vim/tmux 친화.
        val pb = PtyProcessBuilder(arrayOf("/bin/bash", "--login", "-i"))
            .setDirectory(workdir)
            .setEnvironment(
                System.getenv().toMutableMap().apply {
                    put("TERM", "xterm-256color")
                    put("LANG", "en_US.UTF-8")
                    put("LC_ALL", "en_US.UTF-8")
                    put("PS1", "vibe \\W $ ")
                },
            )
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setConsole(false)
        val proc = pb.start()
        val sess = TerminalSession(id = id, workdir = workdir, process = proc, scope = scope)
        sess.start()
        sessions[id] = sess
        scope.launch {
            sess.exit.collect { sessions.remove(id) }
        }
        log.info { "[term $id] spawned bash in $workdir" }
        return sess
    }

    fun get(id: String): TerminalSession? = sessions[id]

    fun list(): List<TerminalSession> = sessions.values.toList()

    fun close(id: String) {
        sessions[id]?.let {
            it.kill()
            sessions.remove(id)
        }
    }

    fun shutdownAll() {
        sessions.values.forEach { it.kill() }
        sessions.clear()
        scope.cancel()
    }
}
