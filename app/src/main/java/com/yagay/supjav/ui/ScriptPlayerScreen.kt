package com.yagay.supjav.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.VideoServer
import com.yagay.supjav.util.Diagnostics
import org.json.JSONObject

/**
 * Explicit user-selected compatibility mode for a site-provided scripted player.
 * Never copies a guessed token into a direct media URL, bypasses CAPTCHA or downloads media.
 * The site runs its own JumpChain and other player scripts in a visible WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScriptPlayerScreen(server: VideoServer) {
    val context = LocalContext.current
    var status by remember(server.id, server.link) { mutableStateOf("正在载入网站播放器") }
    var reload by remember(server.id, server.link) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(server.name + " · 网页兼容播放",
                    style = MaterialTheme.typography.titleSmall)
                Text(status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { reload++ }) { Text("刷新") }
        }
        Text(
            "此模式运行 Supjav 网站和第三方播放器脚本。请按网页提示操作；需要验证的线路仍需人工验证。",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        key(server.link, reload) {
            var webView: WebView? by remember { mutableStateOf(null) }
            DisposableEffect(Unit) {
                onDispose {
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
                        val tokenJson = JSONObject.quote(server.link)
                        val expectedIndex = server.id.toIntOrNull() ?: -1
                        webChromeClient = object : WebChromeClient() {}
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                Diagnostics.log("WEB_PLAY_LOADING host=" + Uri.parse(url).host +
                                    " line=" + server.name)
                                status = "正在加载站点脚本"
                            }
                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest
                            ): Boolean {
                                if (!request.isForMainFrame) return false
                                val host = request.url.host.orEmpty().lowercase()
                                val sameSite = host == "supjav.com" || host.endsWith(".supjav.com")
                                if (!sameSite) {
                                    Diagnostics.log("WEB_BLOCK_MAIN_NAV host=" + host)
                                }
                                return !sameSite
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                val host = Uri.parse(url).host.orEmpty()
                                if (host != "supjav.com" && !host.endsWith(".supjav.com")) return
                                val script = """
                                    (function () {
                                        var controls = Array.from(document.querySelectorAll('.btn-server[data-link]'));
                                        var target = controls.find(function (e) {
                                            return e.getAttribute('data-link') === $tokenJson;
                                        });
                                        if (!target && $expectedIndex >= 0) {
                                            var fallback = controls[$expectedIndex];
                                            if (fallback && fallback.getAttribute('data-link') === $tokenJson) target = fallback;
                                        }
                                        if (!target) return 'NOT_FOUND:' + controls.length;
                                        try {
                                            target.scrollIntoView({block:'center'});
                                            target.click();
                                            var frame = document.querySelector('iframe#video, #player-wrap iframe[src]');
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
                                        " state=" + resultCode + " chars=" + server.link.length)
                                    status = when {
                                        resultCode.startsWith("SELECTED") ->
                                            "线路已选中；点击网页播放按钮"
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
                                if (request.isForMainFrame) {
                                    status = "网页 HTTP " + response.statusCode
                                }
                                Diagnostics.log("WEB_HTTP status=" + response.statusCode +
                                    " main=" + request.isForMainFrame +
                                    " host=" + request.url.host)
                            }
                        }
                        Diagnostics.log("WEB_PLAYER_OPEN line=" + server.name +
                            " tokenChars=" + server.link.length)
                        loadUrl(server.detailUrl)
                    }
                }
            )
        }
    }
}
