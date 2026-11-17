package com.yagay.supjav.ui

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yagay.supjav.data.BrowserHlsDocument
import com.yagay.supjav.data.MediaSourceDetector
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.util.Diagnostics

/**
 * Browser-network HLS fallback using a video-only UI. No third-party player
 * page, ad iframes, Java proxy or direct Media3 source; the selected player's
 * origin supplies the document context. If the CDN refuses WebView too, show
 * the error and return to the original player rather than looping.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EmbeddedHlsPlayer(
    source: PlaybackSource,
    onWebsitePlayer: () -> Unit
) {
    val context = LocalContext.current
    var status by remember(source.url) { mutableStateOf("正在使用网页网络加载 HLS") }
    val document = remember(source.url, source.referer) {
        BrowserHlsDocument.render(source)
    }
    if (document == null) {
        Text("不支持此媒体地址", modifier = Modifier.padding(16.dp))
        TextButton(onClick = onWebsitePlayer) { Text("返回网站播放器") }
        return
    }
    val playerOrigin = remember(source.referer) {
        MediaSourceDetector.origin(source.referer)
    } ?: return
    var webView by remember(source.url) { mutableStateOf<WebView?>(null) }

    DisposableEffect(source.url) {
        onDispose {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(status, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onWebsitePlayer) { Text("返回网站播放器") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    webView = this
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.allowContentAccess = false
                    settings.allowFileAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.userAgentString = SupjavSource.UA
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: android.webkit.WebResourceRequest
                        ): Boolean = request.isForMainFrame

                        override fun onReceivedHttpError(
                            view: WebView, request: android.webkit.WebResourceRequest,
                            response: android.webkit.WebResourceResponse
                        ) {
                            val code = response.statusCode
                            if (code >= 400) Diagnostics.log(
                                "EMBEDDED_HLS_HTTP host=" + request.url.host +
                                    " status=" + code)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                            val code = message?.message()
                                ?.takeIf { it.startsWith("SUPJAV_HLS_STATE:") }
                                ?.removePrefix("SUPJAV_HLS_STATE:") ?: return false
                            Diagnostics.log("EMBEDDED_HLS_STATE code=" + code)
                            status = when (code) {
                                "hlsjs" -> "正在通过 HLS.js 加载"
                                "manifest" -> "已读取 HLS 清单，准备播放"
                                "playing" -> "视频播放中（网页网络）"
                                "buffering" -> "正在缓冲"
                                "tap-play" -> "点击播放按钮开始播放"
                                "native-hls" -> "正在尝试系统网页 HLS"
                                "http-403" -> "网页网络也被拒绝（403），请返回网站播放器"
                                "http-404" -> "媒体地址失效（404），请刷新线路"
                                "fatal-error" -> "HLS 播放失败，请返回网站播放器"
                                "hlsjs-unavailable" -> "HLS.js 不可用，请检查网络"
                                "video-error", "player-exception" ->
                                    "视频加载失败，请返回网站播放器"
                                else -> "正在加载 HLS"
                            }
                            return true
                        }
                    }
                    Diagnostics.log("EMBEDDED_HLS_START host=" +
                        android.net.Uri.parse(source.url).host +
                        " refererHost=" + android.net.Uri.parse(source.referer).host)
                    loadDataWithBaseURL(
                        source.referer,
                        document,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        )
    }
}
