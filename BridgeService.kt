package com.example.mediabridge

import android.content.ComponentName
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通知アクセスを許可すると、システムがこのサービスを常駐させる。
 * 再生中のMediaSession(Spotify/YouTube Music等)を読み取り、PCへWebSocketで送信。
 * PCからのコマンド(play/pause/toggle/next/prev/seek)を transportControls に流す。
 */
class BridgeService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val me by lazy { ComponentName(this, BridgeService::class.java) }
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()

    private lateinit var msm: MediaSessionManager
    private var sessions: List<MediaController> = emptyList()
    private var last: MediaController? = null
    private var ws: WebSocket? = null
    private var connected = false
    private var running = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { setSessions(it) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "status") ws?.cancel() // 設定変更 -> 切断して新設定で再接続
    }

    private val beat = object : Runnable {
        override fun run() {
            if (connected) push() // 3秒ごとに位置を再送して同期ズレを補正
            handler.postDelayed(this, 3000)
        }
    }

    override fun onListenerConnected() {
        running = true
        msm = getSystemService(MediaSessionManager::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        setSessions(msm.getActiveSessions(me))
        connect()
        handler.post(beat)
    }

    override fun onListenerDisconnected() {
        running = false
        handler.removeCallbacks(beat)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        sessions.forEach { it.unregisterCallback(cb) }
        sessions = emptyList()
        ws?.close(1000, null)
        setStatus("停止")
        requestRebind(me)
    }

    // ---- MediaSession ----

    private fun setSessions(list: List<MediaController>?) {
        sessions.forEach { it.unregisterCallback(cb) }
        sessions = (list ?: emptyList()).filter { it.packageName != packageName }
        sessions.forEach { it.registerCallback(cb, handler) }
        push()
    }

    /** 再生中を優先。無ければ直前に選んでいたもの。 */
    private fun current(): MediaController? {
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.sessionToken == last?.sessionToken }
            ?: sessions.firstOrNull()
        last = c
        return c
    }

    private fun push() {
        if (!connected) return
        val j = JSONObject().put("type", "state")
        val c = current()
        if (c == null) {
            ws?.send(j.put("playing", false).toString())
            return
        }
        val md = c.metadata
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (pos < 0) pos = 0
        if (playing && st != null) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        j.put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "")
        j.put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
        j.put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
        j.put("duration_ms", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        j.put("position_ms", pos)
        j.put("playing", playing)
        j.put("speed", (st?.playbackSpeed ?: 1f).toDouble())
        ws?.send(j.toString())
    }

    private fun handleCmd(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (j.optString("type") != "cmd") return
        val c = current() ?: return
        val tc = c.transportControls
        when (j.optString("action")) {
            "play" -> tc.play()
            "pause" -> tc.pause()
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
            "seek" -> tc.seekTo(j.optLong("position_ms"))
        }
    }

    // ---- WebSocket ----

    private fun connect() {
        if (!running) return
        val host = prefs.getString("host", "")?.trim().orEmpty()
        if (host.isEmpty()) {
            setStatus("PCのアドレス未設定")
            handler.postDelayed({ connect() }, 3000)
            return
        }
        val token = prefs.getString("token", "")?.trim().orEmpty()
        val req = try {
            Request.Builder().url("ws://$host").apply {
                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
            }.build()
        } catch (e: IllegalArgumentException) {
            setStatus("アドレス形式が不正")
            handler.postDelayed({ connect() }, 3000)
            return
        }
        setStatus("接続中…")
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post { connected = true; setStatus("接続済み"); push() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { handleCmd(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry("切断")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                retry("接続失敗: ${t.message}")
        })
    }

    private fun retry(why: String) {
        handler.post {
            connected = false
            setStatus("$why (再試行中)")
            handler.postDelayed({ connect() }, 3000)
        }
    }

    private fun setStatus(s: String) {
        prefs.edit().putString("status", s).apply()
    }
}
