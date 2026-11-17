package com.yagay.supjav.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yagay.supjav.data.BrowserHlsDocument
import com.yagay.supjav.data.MediaSourceDetector
import com.yagay.supjav.data.SupjavSource
import com.yagay.supjav.model.PlaybackSource
import com.yagay.supjav.util.Diagnostics

/**
 * The UI and its controls occupy one WebView/video surface, not separate
 * Compose toolbars. Switching orientation keeps the same HLS instance alive.
 * WebView's already-working network request context stays unchanged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EmbeddedHlsPlayer(
    source: PlaybackSource,
    onWebsitePlayer: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val document = remember(source.url, source.referer) {
        BrowserHlsDocument.render(source)
    }
    if (document == null || MediaSourceDetector.origin(source.referer) == null) {
        Text("不支持此媒体地址")
        return
    }

    var webView by remember(source.url) { mutableStateOf<WebView?>(null) }
    var landscape by remember(source.url) { mutableStateOf(false) }
    val originalOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun resetOrientation() {
        if (!landscape) return
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            it.requestedOrientation = originalOrientation
        }
        landscape = false
    }

    DisposableEffect(source.url) {
        onDispose {
            // Back, navigation and source changes must not leave the entire
            // application locked in landscape or immersive mode.
            if (landscape) {
                activity?.let {
                    WindowCompat.getInsetsController(it.window, it.window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                    it.requestedOrientation = originalOrientation
                }
            }
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
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
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        response: android.webkit.WebResourceResponse
                    ) {
                        if (response.statusCode >= 400) {
                            Diagnostics.log("EMBEDDED_HLS_HTTP host=" +
                                request.url.host + " status=" + response.statusCode)
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        val code = message?.message()
                            ?.takeIf { it.startsWith("SUPJAV_HLS_STATE:") }
                            ?.removePrefix("SUPJAV_HLS_STATE:") ?: return false
                        // The only messages emitted by our player document are
                        // predefined state/action codes; never log signed URLs.
                        when (code) {
                            "website" -> {
                                Diagnostics.log("EMBEDDED_HLS_WEBSITE_SELECTED")
                                resetOrientation()
                                onWebsitePlayer()
                            }
                            "rotate" -> {
                                landscape = !landscape
                                activity?.let {
                                    val controller = WindowCompat.getInsetsController(
                                        it.window, it.window.decorView
                                    )
                                    if (landscape) {
                                        it.requestedOrientation =
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        controller.hide(WindowInsetsCompat.Type.systemBars())
                                    } else {
                                        controller.show(WindowInsetsCompat.Type.systemBars())
                                        it.requestedOrientation = originalOrientation
                                    }
                                }
                                Diagnostics.log("EMBEDDED_HLS_ROTATE landscape=" + landscape)
                            }
                            else -> Diagnostics.log("EMBEDDED_HLS_STATE code=" + code)
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
