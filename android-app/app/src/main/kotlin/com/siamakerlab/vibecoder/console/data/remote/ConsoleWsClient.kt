package com.siamakerlab.vibecoder.console.data.remote

import com.siamakerlab.vibecoder.console.data.local.AppPreferences
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional WS connection to `/ws/projects/{id}/console/logs`.
 *
 * Returns a [ConsoleSession] which exposes an inbound `Flow<WsFrame>` and an outbound
 * suspend function for sending [WsFrame.UserPrompt] / [WsFrame.ActionInvoke] over the
 * same socket — saving a roundtrip vs the POST endpoint when the WS is already open.
 *
 * Note: each call to [connect] opens its own connection. The caller is responsible for
 * managing reconnection (the ConsoleRepository's state machine handles that).
 */
@Singleton
class ConsoleWsClient @Inject constructor(
    private val client: HttpClient,
    private val prefs: AppPreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /**
     * Snapshot of an active WS connection.
     *  - [frames] : inbound flow of WsFrame.
     *  - [send]   : outbound sink. Throws if the socket has closed.
     */
    interface ConsoleSession {
        val frames: Flow<WsFrame>
        suspend fun send(frame: WsFrame)
    }

    /**
     * Open a streaming session. The returned [ConsoleSession.frames] is a cold flow —
     * collection drives the connection. When the collector stops, the socket closes.
     */
    suspend fun connect(projectId: String, since: Long): ConsoleSession {
        val session = prefs.session.first()
        val base = (session.serverUrl ?: error("not paired")).trimEnd('/')
        val token = session.token ?: error("no token")
        val path = ApiPath.wsConsoleLogs(projectId) + "?since=$since"
        val wsUrl = (base + path).replaceFirst(Regex("^http"), "ws")
        return RealSession(client, wsUrl, token, json)
    }
}

private class RealSession(
    private val client: HttpClient,
    private val wsUrl: String,
    private val token: String,
    private val json: Json,
) : ConsoleWsClient.ConsoleSession {

    private val outbound = Channel<WsFrame>(Channel.BUFFERED)

    override val frames: Flow<WsFrame> = channelFlow {
        client.webSocket(wsUrl) {
            // 1) auth frame
            send(Frame.Text(json.encodeToString(WsFrame.serializer(), WsFrame.Auth(token))))

            // 2) outbound pump
            val outboundJob = launch {
                for (msg in outbound) {
                    if (!isActive) break
                    runCatching {
                        send(Frame.Text(json.encodeToString(WsFrame.serializer(), msg)))
                    }
                }
            }

            // 3) inbound pump
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val parsed = runCatching {
                        json.decodeFromString(WsFrame.serializer(), frame.readText())
                    }.getOrNull() ?: continue
                    trySend(parsed)
                }
            } finally {
                outboundJob.cancel()
                outbound.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(frame: WsFrame) {
        outbound.send(frame)
    }
}
