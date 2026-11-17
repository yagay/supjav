package com.yagay.supjav.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yagay.supjav.data.MediaSourceDetector
import com.yagay.supjav.data.SelectedMediaSession
import com.yagay.supjav.data.NativeMediaCandidateVerifier
import com.yagay.supjav.data.SitePlayerWrapper
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.model.VideoServer
import com.yagay.supjav.player.WebPlaybackSession
import com.yagay.supjav.util.Diagnostics
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional browser-compatible player and a source-detection bridge to Media3.
 * The site runs its own script in an actual WebView. No CAPTCHA/DRM bypass.
 * Only media declared by the selected frame chain may be offered to Media3.
 * Cookies and signed URLs are never written to diagnostic logs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScriptPlayerScreen(
    server: VideoServer,
    onNativePlay: (PlaybackSource) -> Unit,
    onEmbeddedHlsChanged: (Boolean) -> Unit = {},
    onShowLinePicker: () -> Unit = {},
    onShowPlaylist: () -> Unit = {},
    availableParts: List<String> = emptyList(),
    activePart: String? = null,
    onSelectPart: (String) -> Unit = {},
    autoEnterHls: Boolean = false,
    webPlayback: WebPlaybackSession
) {
    val context = LocalContext.current
    var status by remember(server.id, server.link) { mutableStateOf("正在载入网站播放器") }
    var reload by remember(server.id, server.link) { mutableIntStateOf(0) }
    var detected by remember(server.id, server.link) { mutableStateOf<PlaybackSource?>(null) }
    var nativeProbeStatus by remember(server.id, server.link) { mutableStateOf<String?>(null) }
    var browserHlsSource by remember(server.id, server.link) {
        mutableStateOf<PlaybackSource?>(null)
    }
    var useEmbeddedHls by remember(server.id, server.link) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reportEmbeddedMode by rememberUpdatedState(onEmbeddedHlsChanged)
    LaunchedEffect(autoEnterHls, browserHlsSource?.url) {
        if (autoEnterHls && browserHlsSource != null) useEmbeddedHls = true
    }

    LaunchedEffect(useEmbeddedHls) {
        reportEmbeddedMode(useEmbeddedHls)
    }
    DisposableEffect(server.link) {
        onDispose { reportEmbeddedMode(false) }
    }
    // Root owns system Back for every web-player mode, including embedded
    // HLS. The explicit "website player" control still switches renderers.
    if (useEmbeddedHls && browserHlsSource != null) {
        // Video and its mobile controls occupy the entire playback surface.
        // No extra Compose status/refresh toolbar above the video.
        EmbeddedHlsPlayer(
            source = requireNotNull(browserHlsSource),
            session = webPlayback,
            onShowLinePicker = onShowLinePicker,
            onShowPlaylist = onShowPlaylist,
            availableParts = availableParts,
            activePart = activePart,
            onSelectPart = onSelectPart,
            onWebsitePlayer = {
                useEmbeddedHls = false
                reload++
            }
        )
    } else {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(server.name + " · 网页解析",
                    style = MaterialTheme.typography.titleSmall)
                Text(status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                detected = null
                nativeProbeStatus = null
                browserHlsSource = null
                useEmbeddedHls = false
                reload++
            }) { Text("刷新") }
        }
        val candidate = detected
        if (candidate != null) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("已识别真实媒体地址", style = MaterialTheme.typography.titleSmall)
                        Text(
                            (if (candidate.mimeType.contains("mpegURL")) "HLS" else "MP4") +
                                " · " + (Uri.parse(candidate.url).host ?: "unknown") +
                                "（如果原生返回 403，请继续用网页播放）",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Button(onClick = {
                        Diagnostics.log("WEB_TO_NATIVE line=" + server.name +
                            " host=" + Uri.parse(candidate.url).host +
                            " type=" + candidate.mimeType)
                        onNativePlay(candidate)
                    }) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Text("原生播放")
                    }
                }
            }
        } else {
            Text(
                nativeProbeStatus ?: "正在识别当前线路的 MP4/HLS，并验证原生播放器能否访问。网页播放可以独立使用；人工验证不能自动跳过。",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (browserHlsSource != null && !useEmbeddedHls && detected == null) {
            TextButton(
                onClick = {
                    Diagnostics.log("EMBEDDED_HLS_SELECTED line=" + server.name)
                    useEmbeddedHls = true
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text("尝试内置 HLS 播放（不显示第三方播放器网页）")
            }
        }
        key(server.link, reload) {
            val session = remember(server.link, server.verifiedPlayerUrl, reload) {
                SelectedMediaSession(
                    server.detailUrl,
                    SitePlayerWrapper.fromLegacyToken(server.link),
                    server.verifiedPlayerUrl
                )
            }
            var webView: WebView? by remember { mutableStateOf(null) }
            val handler = remember { Handler(Looper.getMainLooper()) }
            val alive = remember { AtomicBoolean(true) }
            val selected = remember { AtomicBoolean(false) }
            val lineSubmitted = remember { AtomicBoolean(false) }
            val reportedOrigins = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
            val reportedFrames = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
            val reportedMedia = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
            val testedMedia = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
            val testCount = remember { java.util.concurrent.atomic.AtomicInteger(0) }
            val verifier = remember { NativeMediaCandidateVerifier() }
            fun offer(source: PlaybackSource, method: String) {
                handler.post {
                    if (!alive.get() || !selected.get() || detected != null) return@post
                    // Document-start scripts report the same signed URL every
                    // 600ms; probe each exact candidate only once per session.
                    if (!testedMedia.add(source.url) || testCount.incrementAndGet() > 6) return@post
                    nativeProbeStatus = "发现候选媒体，正在验证原生播放是否可用…"
                    Diagnostics.log("WEB_MEDIA_CANDIDATE via=" + method +
                        " host=" + Uri.parse(source.url).host +
                        " refererHost=" + Uri.parse(source.referer).host +
                        " type=" + source.mimeType)
                    scope.launch {
                        val result = verifier.verify(source)
                        if (!alive.get() || !selected.get()) return@launch
                        when (result) {
                            is NativeMediaCandidateVerifier.Result.Ready -> {
                                if (detected == null) {
                                    detected = result.source
                                    nativeProbeStatus = null
                                    status = "原生媒体验证成功，可以尝试 Media3"
                                    Diagnostics.log("WEB_NATIVE_PROBE_READY via=" + method +
                                        " host=" + Uri.parse(result.source.url).host +
                                        " type=" + result.source.mimeType)
                                    Diagnostics.log("WEB_MEDIA_DETECTED via=" + method +
                                        " host=" + Uri.parse(result.source.url).host +
                                        " refererHost=" + Uri.parse(result.source.referer).host +
                                        " type=" + result.source.mimeType)
                                }
                            }
                            is NativeMediaCandidateVerifier.Result.Rejected -> {
                                Diagnostics.log("WEB_NATIVE_PROBE_REJECTED via=" + method +
                                    " host=" + Uri.parse(source.url).host +
                                    " status=" + result.status +
                                    " reason=" + result.reason)
                                if (detected == null) {
                                    if (source.mimeType == "application/x-mpegURL" &&
                                        server.name.trim().uppercase() in setOf("EVS", "LUC") &&
                                        result.status in setOf(403, 416)) {
                                        browserHlsSource = source
                                        Diagnostics.log("EMBEDDED_HLS_AVAILABLE line=" +
                                            server.name + " host=" +
                                            Uri.parse(source.url).host)
                                    }
                                    val reason = when (result.status) {
                                        403 -> "HTTP 403：源站拒绝原生网络请求"
                                        401 -> "HTTP 401：媒体地址需要认证"
                                        404 -> "HTTP 404：候选媒体地址无效或已过期"
                                        429 -> "HTTP 429：请求被限制"
                                        else -> "原生验证失败：" + (result.status?.toString()
                                            ?: result.reason.take(30))
                                    }
                                    nativeProbeStatus = "发现 HLS/MP4，但" + reason +
                                        "。可继续网页播放或返回更换线路。"
                                    status = "网页播放器已加载，原生媒体验证未通过"
                                }
                            }
                        }
                    }
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    alive.set(false)
                    webView?.let { webPlayback.detachProvider(it) }
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.destroy()
                    webView = null
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        webPlayback.attachProvider(this)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            userAgentString = SupjavSource.UA
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        val frameReporting = WebViewFeature.isFeatureSupported(
                            WebViewFeature.WEB_MESSAGE_LISTENER
                        ) && WebViewFeature.isFeatureSupported(
                            WebViewFeature.DOCUMENT_START_SCRIPT
                        )
                        if (frameReporting) {
                            // A message from an untrusted frame is inert. Both frame origin
                            // and its parent-selected iframe chain must be verified.
                            WebViewCompat.addWebMessageListener(
                                this, "SupjavMediaProbe", setOf("*")
                            ) { _, message, sourceOrigin, _, _ ->
                                if (!alive.get() || !selected.get()) return@addWebMessageListener
                                val obj = runCatching {
                                    JSONObject(message.data.orEmpty())
                                }.getOrNull() ?: return@addWebMessageListener
                                val document = obj.optString("page").take(4096)
                                val claimedOrigin = sourceOrigin.toString()
                                val documentHost = Uri.parse(document).host.orEmpty()
                                if (MediaSourceDetector.origin(document) !=
                                    MediaSourceDetector.origin(claimedOrigin)) {
                                    return@addWebMessageListener
                                }
                                val docRef = obj.optString("ref")
                                val admitted = session.admitDocument(document, docRef)
                                if (reportedOrigins.add(documentHost)) {
                                    Diagnostics.log("WEB_FRAME_REPORT host=" + documentHost +
                                        " refHost=" + Uri.parse(docRef).host +
                                        " admitted=" + admitted)
                                }
                                val child = obj.optString("frame")
                                if (child.isNotEmpty()) {
                                    val frameHost = Uri.parse(child).host.orEmpty()
                                    val trusted = session.trustFrame(document, child)
                                    if (reportedFrames.add(documentHost + ">" + frameHost)) {
                                        Diagnostics.log("WEB_FRAME_CHILD from=" + documentHost +
                                            " to=" + frameHost + " trusted=" + trusted)
                                    }
                                }
                                val media = obj.optJSONArray("media") ?: return@addWebMessageListener
                                if (media.length() > 0 && reportedMedia.add(documentHost)) {
                                    Diagnostics.log("WEB_MEDIA_DECLARED host=" + documentHost +
                                        " count=" + media.length() + " trusted=" + admitted)
                                }
                                for (i in 0 until minOf(media.length(), 12)) {
                                    val source = session.fromPageReport(
                                        claimedOrigin, document, media.optString(i)
                                    ) ?: continue
                                    offer(source, "script")
                                }
                            }
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                """
                                (function() {
                                    if (window.__supjavMediaProbe) return;
                                    window.__supjavMediaProbe = true;
                                    function report() {
                                        var media = [];
                                        function add(u) {
                                            if (u && u.indexOf('https://') === 0 &&
                                                media.indexOf(u) < 0) media.push(u);
                                        }
                                        try {
                                            var linksObj = (typeof links !== 'undefined') ?
                                                links : window.links;
                                            if (linksObj && typeof linksObj === 'object') {
                                                ['hls2','hls4','hls3'].forEach(function(k) {
                                                    add(linksObj[k]);
                                                });
                                            }
                                        } catch(e) {}
                                        try {
                                            if (typeof jwplayer === 'function') {
                                                var playlist = jwplayer().getPlaylist()[0] || {};
                                                add(playlist.file);
                                                (playlist.sources || []).forEach(function(s) {add(s.file);});
                                            }
                                        } catch(e) {}
                                        document.querySelectorAll('video').forEach(function(v) {
                                            add(v.currentSrc);
                                            add(v.src);
                                            v.querySelectorAll('source[src]').forEach(function(s) {
                                                add(s.src);
                                            });
                                        });
                                        var f = document.querySelector(
                                            'iframe#supjav-native-frame, iframe#video, #player-wrap iframe[src], iframe#player, ' +
                                            'iframe[src*="/supjav.php"], iframe[src*="/embed/"], iframe[src*="/e/"]'
                                        );
                                        try {
                                            SupjavMediaProbe.postMessage(JSON.stringify({
                                                page: location.href,
                                                ref: document.referrer || '',
                                                frame: f && f.src || '',
                                                media: media.slice(0, 12)
                                            }));
                                        } catch(e) {}
                                    }
                                    setInterval(report, 600);
                                    document.addEventListener('DOMContentLoaded', report);
                                })();
                                """.trimIndent(), setOf("*")
                            )
                        } else {
                            Diagnostics.log("WEB_FRAME_REPORT_UNAVAILABLE")
                        }

                        val rootProbe = object : Runnable {
                            var attempts = 0
                            override fun run() {
                                if (!alive.get() || attempts >= 14) return
                                attempts++
                                if (selected.get()) {
                                    evaluateJavascript("""
                                        (function() {
                                            var frames = document.querySelectorAll('iframe[src]');
                                            var selected = document.querySelector(
                                                'iframe#supjav-native-frame, iframe#video, #player-wrap iframe[src], iframe#player');
                                            return JSON.stringify({
                                                count: frames.length,
                                                frame: selected && selected.src || ''
                                            });
                                        })()
                                    """.trimIndent()) { raw ->
                                        val obj = runCatching {
                                            JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                                        }.getOrNull()
                                        if (obj != null) {
                                            val frame = obj.optString("frame")
                                            val host = Uri.parse(frame).host.orEmpty()
                                            val trusted = if (frame.isNotBlank())
                                                session.trustFrame(server.detailUrl, frame) else false
                                            if (attempts <= 4 || (host.isNotBlank() &&
                                                    reportedFrames.add("root>" + host))) {
                                                Diagnostics.log("WEB_ROOT_PROBE line=" + server.name +
                                                    " frames=" + obj.optInt("count") +
                                                    " playerHost=" + host +
                                                    " trusted=" + trusted)
                                            }
                                        }
                                    }
                                }
                                if (attempts < 14) postDelayed(this, 1300)
                            }
                        }
                        postDelayed(rootProbe, 1000)
                        val tokenJson = JSONObject.quote(server.link)
                        val fallbackJson = JSONObject.quote(
                            SitePlayerWrapper.fromLegacyToken(server.link).orEmpty()
                        )
                        val expectedIndex = server.id.toIntOrNull() ?: -1
                        webChromeClient = object : WebChromeClient() {}
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                Diagnostics.log("WEB_PLAY_LOADING host=" + Uri.parse(url).host +
                                    " line=" + server.name)
                                status = "正在加载网站脚本"
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest
                            ): Boolean {
                                if (!request.isForMainFrame) return false
                                val host = request.url.host.orEmpty().lowercase()
                                val sameSite = host == "supjav.com" || host.endsWith(".supjav.com")
                                if (!sameSite) Diagnostics.log("WEB_BLOCK_MAIN_NAV host=" + host)
                                return !sameSite
                            }

                            override fun shouldInterceptRequest(
                                view: WebView, request: WebResourceRequest
                            ): WebResourceResponse? {
                                if (!alive.get() || !selected.get()) return null
                                val ref = request.requestHeaders.entries
                                    .firstOrNull { it.key.equals("Referer", true) }?.value
                                val source = session.fromNetworkRequest(
                                    request.url.toString(), ref
                                )
                                if (source != null) offer(source, "network")
                                return null
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                val host = Uri.parse(url).host.orEmpty()
                                if (host != "supjav.com" && !host.endsWith(".supjav.com")) return
                                // A repeated page-finished event must not reload the
                                // same iframe and discard its player/media session.
                                if (!lineSubmitted.compareAndSet(false, true)) return
                                // Calling the site's original JumpChain is preferred.
                                // If it does not return a wrapper, use only the documented
                                // legacy hex-token iframe form; never a top-level GET.
                                val script = """
                                    (function () {
                                        var controls = Array.from(document.querySelectorAll(
                                            '.btn-server[data-link]'));
                                        var target = controls.find(function (e) {
                                            return e.getAttribute('data-link') === $tokenJson;
                                        });
                                        if (!target && $expectedIndex >= 0) {
                                            var fallback = controls[$expectedIndex];
                                            if (fallback &&
                                                fallback.getAttribute('data-link') === $tokenJson) {
                                                target = fallback;
                                            }
                                        }
                                        if (!target) return JSON.stringify({
                                            state:'NOT_FOUND', count:controls.length
                                        });
                                        var token = target.getAttribute('data-link');
                                        var strategy = 'none';
                                        var wrapper = '';
                                        var jumpAvailable = typeof window.JumpChain === 'function';
                                        if (jumpAvailable) {
                                            try {
                                                var output = window.JumpChain(token);
                                                if (typeof output === 'string') {
                                                    var m = output.match(
                                                        /https:\/\/[^\s"'<>\x60]+/i);
                                                    wrapper = m ? m[0].replace(/&amp;/g,'&') : '';
                                                    strategy = 'site-jump';
                                                }
                                            } catch (e) { strategy = 'site-jump-error'; }
                                        }
                                        function isWrapper(raw) {
                                            try {
                                                var u = new URL(raw, location.href);
                                                return u.protocol === 'https:' &&
                                                    (u.hostname === 'supremejav.com' ||
                                                        u.hostname.endsWith('.supremejav.com')) &&
                                                    u.pathname === '/supjav.php' &&
                                                    u.searchParams.has('c');
                                            } catch (e) { return false; }
                                        }
                                        if (!isWrapper(wrapper)) {
                                            wrapper = $fallbackJson;
                                            strategy = wrapper ? 'legacy-token' : 'none';
                                        }
                                        if (isWrapper(wrapper)) {
                                            var host = document.getElementById('supjav-native-host');
                                            if (!host) {
                                                host = document.createElement('div');
                                                host.id = 'supjav-native-host';
                                                host.style.cssText =
                                                    'position:fixed;inset:0;z-index:2147483000;' +
                                                    'background:#000;overflow:hidden';
                                                (document.body || document.documentElement)
                                                    .appendChild(host);
                                            }
                                            var frame = document.getElementById('supjav-native-frame');
                                            if (!frame) {
                                                frame = document.createElement('iframe');
                                                frame.id = 'supjav-native-frame';
                                                frame.style.cssText =
                                                    'width:100%;height:100%;border:0;display:block';
                                                frame.allow = 'autoplay;fullscreen;picture-in-picture';
                                                frame.allowFullscreen = true;
                                                host.appendChild(frame);
                                            }
                                            if (frame.src !== wrapper) frame.src = wrapper;
                                            return JSON.stringify({
                                                state:'FRAME_READY',
                                                strategy:strategy,
                                                host:new URL(wrapper).hostname,
                                                jumpAvailable:jumpAvailable
                                            });
                                        }
                                        try { target.click(); } catch (e) {}
                                        return JSON.stringify({
                                            state:'CLICK_FALLBACK',
                                            strategy:strategy,
                                            jumpAvailable:jumpAvailable,
                                            count:controls.length
                                        });
                                    })()
                                """.trimIndent()
                                view.evaluateJavascript(script) { raw ->
                                    if (!alive.get()) return@evaluateJavascript
                                    val result = runCatching {
                                        JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                                    }.getOrNull()
                                    val state = result?.optString("state").orEmpty()
                                    val strategy = result?.optString("strategy").orEmpty()
                                    val hostName = result?.optString("host").orEmpty()
                                    val jumpAvailable = result?.optBoolean("jumpAvailable") ?: false
                                    Diagnostics.log("WEB_WRAPPER_RESULT line=" + server.name +
                                        " state=" + state + " strategy=" + strategy +
                                        " host=" + hostName + " jumpAvailable=" + jumpAvailable +
                                        " tokens=" + server.link.length)
                                    selected.set(state == "FRAME_READY" || state == "CLICK_FALLBACK")
                                    if (!selected.get()) lineSubmitted.set(false)
                                    if (state == "FRAME_READY") {
                                        val wrapper = SitePlayerWrapper.fromLegacyToken(server.link)
                                        // Site JS may return a different valid wrapper; root
                                        // probe will register its exact current iframe URL.
                                        if (strategy == "legacy-token" && wrapper != null) {
                                            session.trustFrame(server.detailUrl, wrapper)
                                        }
                                        status = "已创建专用播放器，正在检测媒体流"
                                    } else if (state == "CLICK_FALLBACK") {
                                        status = "网站脚本未提供可用中转地址，正在尝试原页面"
                                    } else {
                                        status = "未找到对应线路，请尝试刷新"
                                    }
                                }
                            }
                            override fun onReceivedError(
                                view: WebView, request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (request.isForMainFrame) status = "网页加载失败：" + error.errorCode
                                Diagnostics.log("WEB_ERROR main=" + request.isForMainFrame +
                                    " code=" + error.errorCode + " host=" + request.url.host)
                            }
                            override fun onReceivedHttpError(
                                view: WebView, request: WebResourceRequest,
                                response: WebResourceResponse
                            ) {
                                if (request.isForMainFrame) status = "网页 HTTP " + response.statusCode
                                Diagnostics.log("WEB_HTTP status=" + response.statusCode +
                                    " main=" + request.isForMainFrame +
                                    " host=" + request.url.host)
                            }
                        }
                        if (server.verifiedPlayerUrl != null) {
                            Diagnostics.log("WEB_VERIFIED_PLAYER line=" + server.name +
                                " host=" + Uri.parse(server.verifiedPlayerUrl).host)
                        }
                        Diagnostics.log("WEB_PLAYER_OPEN line=" + server.name +
                            " tokenChars=" + server.link.length +
                            " frameReport=" + frameReporting)
                        loadUrl(server.detailUrl)
                    }
                }
            )
        }
    }
    }
}
