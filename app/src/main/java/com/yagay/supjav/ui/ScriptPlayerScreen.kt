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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yagay.supjav.data.MediaSourceDetector
import com.yagay.supjav.data.SelectedMediaSession
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.model.VideoServer
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
    onNativePlay: (PlaybackSource) -> Unit
) {
    val context = LocalContext.current
    var status by remember(server.id, server.link) { mutableStateOf("正在载入网站播放器") }
    var reload by remember(server.id, server.link) { mutableIntStateOf(0) }
    var detected by remember(server.id, server.link) { mutableStateOf<PlaybackSource?>(null) }

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
                "正在从当前线路识别可播放的 MP4/HLS。可以直接使用下方网页播放器；人工验证不能自动跳过。",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        key(server.link, reload) {
            val session = remember(server.link, reload) { SelectedMediaSession(server.detailUrl) }
            var webView: WebView? by remember { mutableStateOf(null) }
            val handler = remember { Handler(Looper.getMainLooper()) }
            val alive = remember { AtomicBoolean(true) }
            val selected = remember { AtomicBoolean(false) }
            fun offer(source: PlaybackSource, method: String) {
                handler.post {
                    if (!alive.get() || !selected.get()) return@post
                    val prev = detected
                    val preferNew = prev == null ||
                        (method == "script" && prev.mimeType == "video/mp4" &&
                            source.mimeType == "application/x-mpegURL")
                    if (!preferNew) return@post
                    detected = source
                    status = "已发现媒体源，可尝试原生播放"
                    Diagnostics.log("WEB_MEDIA_DETECTED via=" + method +
                        " host=" + Uri.parse(source.url).host +
                        " refererHost=" + Uri.parse(source.referer).host +
                        " type=" + source.mimeType)
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    alive.set(false)
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
                                if (MediaSourceDetector.origin(document) !=
                                    MediaSourceDetector.origin(claimedOrigin)) {
                                    return@addWebMessageListener
                                }
                                val child = obj.optString("frame")
                                if (child.isNotEmpty()) session.trustFrame(document, child)
                                val media = obj.optJSONArray("media") ?: return@addWebMessageListener
                                for (i in 0 until minOf(media.length(), 12)) {
                                    val source = session.fromPageReport(
                                        claimedOrigin, document, media.optString(i)
                                    ) ?: continue
                                    offer(source, "script")
                                    break
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
                                            if (u && /^https:\\/\\//i.test(u) &&
                                                media.indexOf(u) < 0) media.push(u);
                                        }
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
                                            'iframe#video, #player-wrap iframe[src], iframe#player, ' +
                                            'iframe[src*="/supjav.php"], iframe[src*="/embed/"], iframe[src*="/e/"]'
                                        );
                                        try {
                                            SupjavMediaProbe.postMessage(JSON.stringify({
                                                page: location.href,
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

                        val tokenJson = JSONObject.quote(server.link)
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
                                val script = """
                                    (function () {
                                        var controls = Array.from(document.querySelectorAll(
                                            '.btn-server[data-link]'));
                                        var target = controls.find(function (e) {
                                            return e.getAttribute('data-link') === $tokenJson;
                                        });
                                        if (!target && $expectedIndex >= 0) {
                                            var fallback = controls[$expectedIndex];
                                            if (fallback && fallback.getAttribute('data-link') === $tokenJson) {
                                                target = fallback;
                                            }
                                        }
                                        if (!target) return 'NOT_FOUND:' + controls.length;
                                        try {
                                            target.scrollIntoView({block:'center'});
                                            target.click();
                                            var frame = document.querySelector(
                                                'iframe#video, #player-wrap iframe[src]');
                                            if (frame) setTimeout(function () {
                                                frame.scrollIntoView({block:'start'});
                                            }, 700);
                                            return frame ? 'SELECTED_FRAME' : 'SELECTED';
                                        } catch (err) { return 'SCRIPT_ERROR'; }
                                    })()
                                """.trimIndent()
                                view.evaluateJavascript(script) { result ->
                                    val resultCode = result.trim('"').take(60)
                                    Diagnostics.log("WEB_LINE_SELECT line=" + server.name +
                                        " state=" + resultCode +
                                        " chars=" + server.link.length)
                                    selected.set(resultCode.startsWith("SELECTED"))
                                    status = when {
                                        resultCode.startsWith("SELECTED") ->
                                            "已切换网站线路，正在识别视频源"
                                        resultCode.startsWith("NOT_FOUND") ->
                                            "未找到对应线路，请在网页中手动选择"
                                        else -> "播放器脚本执行失败，请尝试刷新"
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
