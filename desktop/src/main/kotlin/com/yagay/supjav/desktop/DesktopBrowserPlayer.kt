package com.yagay.supjav.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.awt.SwingPanel
import ca.weblite.webview.swing.WebViewComponent
import com.yagay.supjav.model.VideoItem
import com.yagay.supjav.model.VideoServer
import com.yagay.supjav.util.Diagnostics
import java.awt.BorderLayout
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Browser mode loads the site's real detail document, preserving the site's
 * JavaScript, relative iframe/referrer chain, and legitimate verification.
 * Do not load the opaque /supjav.php wrapper as a top-level document:
 * some hosts require the original site iframe context.
 */
object DesktopBrowserPolicy {
    fun siteDetailUrl(video: VideoItem): String? = runCatching {
        val uri = URI(video.detailUrl)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
            host !in setOf("supjav.com", "www.supjav.com") ||
            !uri.path.orEmpty().matches(Regex("^/[0-9]+\\.html$")) ||
            uri.rawQuery != null || uri.rawFragment != null) null
        else uri.toString()
    }.getOrNull()

    fun preferBrowser(server: VideoServer): Boolean {
        val line = server.name.trim().uppercase(Locale.ROOT)
        return line == "EVS" || line == "LUC"
    }

    fun safeSiteToken(raw: String?): String? = raw?.takeIf {
        it.matches(Regex("[A-Za-z0-9_-]{3,512}"))
    }
}

/** Edge WebView2 on Windows, embedded inside the same Compose player pane. */
@Composable
fun DesktopBrowserPlayer(
    url: String,
    serverToken: String?,
    modifier: Modifier = Modifier
) {
    // One heavyweight WebView per active browser document. Do not reuse VLC's
    // AWT surface or cause setUrl() to reload on every Compose recomposition.
    key(url) {
        val peer = remember(url) { AtomicReference<WebViewComponent?>() }
        DisposableEffect(url) {
            onDispose {
                SwingUtilities.invokeLater {
                    runCatching { peer.getAndSet(null)?.dispose() }
                        .onFailure {
                            Diagnostics.log("DESKTOP_WEBVIEW_DISPOSE_FAILED type=" +
                                it.javaClass.simpleName)
                        }
                }
            }
        }
        SwingPanel(
            modifier = modifier.fillMaxSize(),
            background = Color.Black,
            factory = {
                try {
                    WebViewComponent.create().apply {
                        // Ad popups and unrelated new windows must never obscure
                        // the desktop application or spawn external pages.
                        setPopupHandler(null)
                        // Select the same exact data-link button as Android.
                        // Inject only opaque ASCII tokens on Supjav's OWN site,
                        // never into unrelated frame origins.
                        DesktopBrowserPolicy.safeSiteToken(serverToken)?.let { token ->
                            addOnBeforeLoad("""
                                (() => {
                                  if (!['supjav.com','www.supjav.com']
                                      .includes(location.hostname)) return;
                                  document.addEventListener('DOMContentLoaded', () => {
                                    let attempts = 0;
                                    const timer = setInterval(() => {
                                      const element = [...document.querySelectorAll(
                                        'a[data-link],button[data-link]'
                                      )].find(el => el.getAttribute('data-link') === '$token');
                                      if (element) {
                                        element.click();
                                        element.scrollIntoView({
                                          behavior:'instant', block:'center'
                                        });
                                        clearInterval(timer);
                                      } else if (++attempts > 24) clearInterval(timer);
                                    }, 250);
                                  }, {once:true});
                                })();
                            """.trimIndent())
                        }
                        setUrl(url)
                        peer.set(this)
                        Diagnostics.log("DESKTOP_WEBVIEW_CREATED host=supjav.com")
                    }
                } catch (error: Throwable) {
                    Diagnostics.log("DESKTOP_WEBVIEW_INIT_FAILED type=" +
                        error.javaClass.simpleName)
                    JPanel(BorderLayout()).apply {
                        background = java.awt.Color.BLACK
                        add(JLabel("Edge WebView2 初始化失败，请安装或修复 WebView2 Runtime"),
                            BorderLayout.CENTER)
                    }
                }
            }
        )
    }
}
