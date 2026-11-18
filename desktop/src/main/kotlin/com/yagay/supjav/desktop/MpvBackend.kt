package com.yagay.supjav.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.util.Diagnostics
import java.awt.Canvas
import java.awt.Color
import java.awt.Component
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.swing.Swing
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * JNA libmpv adapter based on the public mpv-player/mpv-examples libmpv/java
 * interface. Uses the Windows HWND (wid) embedding mode, not a foreign app
 * window or a website. The libmpv binary is NOT bundled in this source tree.
 */
private interface MpvApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_set_option_string(handle: Pointer, key: String, value: String): Int
    fun mpv_set_property_string(handle: Pointer, key: String, value: String): Int
    fun mpv_get_property_string(handle: Pointer, key: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(handle: Pointer, args: Array<String?>): Int
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer?
}

private class MpvEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
    override fun getFieldOrder(): List<String> =
        listOf("event_id", "error", "reply_userdata", "data")
    init { read() }
}

internal object MpvRuntime {
    /** Look only in an explicit install directory, then the native loader. */
    fun candidates(customPath: String?, dataDir: Path): List<String> {
        val names = listOf("libmpv-2.dll", "mpv-2.dll", "mpv-1.dll")
        val locations = listOfNotNull(
            customPath?.trim()?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }?.toString(),
            *names.map { dataDir.resolve("mpv").resolve(it) }
                .filter { Files.isRegularFile(it) }.map { it.toString() }.toTypedArray()
        )
        return locations + listOf("libmpv-2", "mpv-2", "mpv-1")
    }

    fun origin(referer: String): String? = runCatching {
        val parsed = URI(referer)
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) null
        else "https://" + parsed.host +
            (if (parsed.port == -1) "" else ":" + parsed.port)
    }.getOrNull()
}

/**
 * Embedded MPV engine. A single AWT Canvas is the parent window supplied to
 * libmpv; only load the stream AFTER it has a real Windows native handle.
 */
class MpvBackend : DesktopNativePlayer {
    private val native: MpvApi? = run {
        var loaded: MpvApi? = null
        val possible = MpvRuntime.candidates(
            System.getenv("SUPJAV_MPV_DLL"), DesktopVideoStore.defaultDirectory()
        )
        for (location in possible) {
            try {
                val asPath = runCatching { Path.of(location) }.getOrNull()
                if (asPath != null && Files.isRegularFile(asPath)) {
                    NativeLibrary.addSearchPath("libmpv-2", asPath.parent.toString())
                }
                loaded = Native.load(location, MpvApi::class.java)
                Diagnostics.log("DESKTOP_MPV_LIBRARY_READY")
                break
            } catch (_: LinkageError) {
                // Nothing installed here. No network downloads and no crash.
            } catch (_: Exception) {
                // Native runtime is optional, VLC remains available.
            }
        }
        if (loaded == null) Diagnostics.log("DESKTOP_MPV_LIBRARY_MISSING")
        loaded
    }
    private val surface = Canvas().apply {
        background = Color.BLACK
        isFocusable = true
    }
    override val component: Component? get() = if (available) surface else null
    override val available: Boolean get() = native != null
    override val engineName = "MPV"
    override var phase by mutableStateOf(PlayerPhase.IDLE)
        private set
    override val playing get() = phase in setOf(PlayerPhase.PLAYING, PlayerPhase.BUFFERING)
    override var errorMessage by mutableStateOf<String?>(null)
        private set
    override var feedback by mutableStateOf("")
    override var brightness by mutableStateOf(0.5f)
        private set
    override var playbackRate by mutableStateOf(1f)
        private set

    private var handle: Pointer? = null
    private var eventThread: Thread? = null
    private val eventsAlive = AtomicBoolean(false)

    private fun dispatch(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block()
        else SwingUtilities.invokeLater(block)
    }
    private fun property(name: String): String? {
        val api = native ?: return null
        val h = handle ?: return null
        return runCatching {
            val value = api.mpv_get_property_string(h, name) ?: return null
            try { value.getString(0, "UTF-8") } finally { api.mpv_free(value) }
        }.getOrNull()
    }

    private fun setProperty(name: String, value: String): Boolean {
        val api = native ?: return false
        val h = handle ?: return false
        return runCatching {
            api.mpv_set_property_string(h, name, value) >= 0
        }.getOrDefault(false)
    }

    override suspend fun playWhenSurfaceReady(source: PlaybackSource): Boolean {
        val api = native ?: return false
        if (!source.url.startsWith("https://") ||
            !source.referer.startsWith("https://")) return false
        val windowId = withTimeoutOrNull(8_000L) {
            while (true) {
                val result = withContext(Dispatchers.Swing) {
                    if (surface.isDisplayable && surface.isShowing)
                        runCatching { Native.getComponentID(surface) }.getOrNull()
                    else null
                }
                if (result != null && result != 0L) return@withTimeoutOrNull result
                delay(60)
            }
        }
        if (windowId == null) {
            dispatch {
                phase = PlayerPhase.ERROR
                errorMessage = "MPV 视频画面没有完成初始化"
            }
            Diagnostics.log("DESKTOP_MPV_SURFACE_TIMEOUT")
            return false
        }
        val nativeWindowId = windowId and 0xFFFFFFFFL
        return withContext(Dispatchers.IO) {
            try {
                destroyHandle()
                val h = api.mpv_create() ?: error("mpv_create returned null")
                handle = h
                fun option(name: String, value: String) {
                    val code = api.mpv_set_option_string(h, name, value)
                    if (code < 0) error("MPV option " + name + " status=" + code)
                }
                option("wid", nativeWindowId.toString())
                option("vo", "gpu")
                option("hwdec", "auto-safe")
                option("osc", "no")
                option("input-default-bindings", "no")
                option("input-vo-keyboard", "no")
                option("terminal", "no")
                option("referrer", source.referer)
                option("user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                if (source.includeOrigin) {
                    MpvRuntime.origin(source.referer)?.let {
                        option("http-header-fields", "Origin: " + it)
                    }
                }
                val initResult = api.mpv_initialize(h)
                if (initResult < 0)
                    error("mpv_initialize status=" + initResult)
                dispatch {
                    phase = PlayerPhase.OPENING
                    errorMessage = null
                }
                val result = api.mpv_command(
                    h, arrayOf("loadfile", source.url, "replace", null)
                )
                if (result < 0)
                    error("mpv_command loadfile status=" + result)
                runEvents(api, h)
                Diagnostics.log("DESKTOP_MPV_PLAY_REQUEST type=" + source.mimeType +
                    " includeOrigin=" + source.includeOrigin)
                true
            } catch (error: Throwable) {
                dispatch {
                    phase = PlayerPhase.ERROR
                    errorMessage = "MPV 无法启动该视频：" + error.javaClass.simpleName
                }
                Diagnostics.log("DESKTOP_MPV_START_FAILED type=" +
                    error.javaClass.simpleName + " detail=" +
                    error.message.orEmpty().take(90))
                destroyHandle()
                false
            }
        }
    }

    private fun runEvents(api: MpvApi, h: Pointer) {
        eventsAlive.set(true)
        eventThread = thread(
            name = "Supjav-MPV-Events", isDaemon = true
        ) {
            while (eventsAlive.get()) {
                try {
                    val eventPointer = api.mpv_wait_event(h, .15) ?: continue
                    val event = MpvEvent(eventPointer)
                    val next = when (event.event_id) {
                        6 -> PlayerPhase.OPENING         // START_FILE
                        8, 21 -> PlayerPhase.PLAYING     // FILE_LOADED, PLAYBACK_RESTART
                        7 -> if (event.error < 0) PlayerPhase.ERROR
                             else PlayerPhase.FINISHED
                        11 -> PlayerPhase.STOPPED       // IDLE
                        else -> null
                    }
                    if (next != null) dispatch {
                        if (h == handle) {
                            phase = next
                            if (next == PlayerPhase.ERROR) {
                                errorMessage = "MPV 播放失败，请尝试另一条线路"
                                Diagnostics.log("DESKTOP_MPV_NATIVE_ERROR")
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (eventsAlive.get()) Diagnostics.log("DESKTOP_MPV_EVENTS_INTERRUPTED")
                    break
                }
            }
        }
    }

    @Synchronized
    private fun destroyHandle() {
        eventsAlive.set(false)
        val t = eventThread
        eventThread = null
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(400) }
        }
        val previous = handle
        handle = null
        if (previous != null) runCatching {
            native?.mpv_terminate_destroy(previous)
        }
    }
    override fun stop() {
        destroyHandle()
        dispatch { phase = PlayerPhase.STOPPED }
    }
    override fun toggle() {
        setProperty("pause",
            if (property("pause") == "yes") "no" else "yes")
    }
    override fun seek(deltaMs: Long) {
        val api = native ?: return
        val h = handle ?: return
        runCatching {
            api.mpv_command(h, arrayOf("seek", (deltaMs / 1000.0).toString(), "relative", null))
        }
    }
    override fun position(): Long = ((property("time-pos")
        ?.toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
    override fun duration(): Long = ((property("duration")
        ?.toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
    override fun seekTo(ms: Long) {
        val api = native ?: return
        val h = handle ?: return
        runCatching {
            api.mpv_command(h, arrayOf("seek", (ms / 1000.0).toString(), "absolute", null))
        }
    }
    override fun speed(rate: Float) {
        if (rate.isFinite() && rate in 0.5f..4f &&
            setProperty("speed", rate.toString())) playbackRate = rate
    }
    override fun volumePercent() = (property("volume")
        ?.toFloatOrNull()?.roundToInt() ?: 50).coerceIn(0, 100)
    override fun setVolumePercent(value: Int) {
        if (setProperty("volume", value.coerceIn(0, 100).toString()))
            feedback = "音量 " + volumePercent() + "%"
    }
    override fun updateBrightness(value: Float) {
        val normalized = value.coerceIn(0.1f, 1f)
        val mpvBrightness = ((normalized - 0.5f) * 150f).roundToInt()
        if (setProperty("brightness", mpvBrightness.toString())) {
            brightness = normalized
            feedback = "画面亮度 " + (normalized * 100).roundToInt() + "%"
        }
    }
    override fun close() {
        stop()
    }
}
