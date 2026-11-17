package com.yagay.supjav.data

import com.yagay.supjav.model.PlaybackSource
import java.util.Base64

/**
 * Small isolated WebView/Hls.js playback document. It runs in the selected
 * player's HTTPS origin to preserve browser request context, unlike Media3.
 *
 * It is a fallback, not a way around a CDN HTTP 403 or a human challenge.
 * A source URL is encoded before injection so it can never close a <script>.
 */
internal object BrowserHlsDocument {
    fun render(source: PlaybackSource): String? {
        if (source.mimeType != "application/x-mpegURL" ||
            MediaSourceDetector.origin(source.url) == null ||
            MediaSourceDetector.origin(source.referer) == null) return null
        val encoded = Base64.getEncoder().encodeToString(source.url.toByteArray(Charsets.UTF_8))
        return """
            <!doctype html>
            <html><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1.0">
            <style>
            html,body {margin:0;background:#000;color:#fff;width:100%;height:100%}
            video {width:100%;height:100%;object-fit:contain;background:#000}
            </style>
            </head><body>
            <video id="video" controls playsinline webkit-playsinline preload="metadata"
                   controlsList="nodownload"></video>
            <script src="https://cdn.jsdelivr.net/npm/hls.js@1.6.15/dist/hls.min.js"></script>
            <script>
            (function() {
              'use strict';
              var video = document.getElementById('video');
              var mediaUrl = atob('$encoded');
              function state(code) { console.log('SUPJAV_HLS_STATE:' + code); }
              video.addEventListener('playing', function() {state('playing');});
              video.addEventListener('waiting', function() {state('buffering');});
              video.addEventListener('error', function() {state('video-error');});
              try {
                if (window.Hls && Hls.isSupported()) {
                  state('hlsjs');
                  var player = new Hls({enableWorker:false, maxBufferLength:20});
                  player.on(Hls.Events.MEDIA_ATTACHED, function(){player.loadSource(mediaUrl);});
                  player.on(Hls.Events.MANIFEST_PARSED, function() {
                    state('manifest');
                    video.play().catch(function(){state('tap-play');});
                  });
                  player.on(Hls.Events.ERROR, function(event, error) {
                    if (!error || !error.fatal) return;
                    var code = (error.response && error.response.code) || 0;
                    state(code === 403 ? 'http-403' :
                          code === 404 ? 'http-404' : 'fatal-error');
                    player.destroy();
                  });
                  player.attachMedia(video);
                } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                  state('native-hls');
                  video.src = mediaUrl;
                  video.play().catch(function(){state('tap-play');});
                } else {
                  state('hlsjs-unavailable');
                }
              } catch (e) {
                state('player-exception');
              }
            })();
            </script></body></html>
        """.trimIndent()
    }
}
