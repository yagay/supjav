package com.yagay.supjav.data

import com.yagay.supjav.model.PlaybackSource
import java.util.Base64

/**
 * Video-first mobile controls rendered INSIDE the HLS.js video surface.
 * All player actions stay in WebView; this does not change the proven
 * browser-network path used by EVS/LUC.
 *
 * The signed media URL is base64 encoded so it cannot terminate a script.
 */
internal object BrowserHlsDocument {
    fun render(source: PlaybackSource, resumePositionMs: Long = 0L): String? {
        if (source.mimeType != "application/x-mpegURL" ||
            MediaSourceDetector.origin(source.url) == null ||
            MediaSourceDetector.origin(source.referer) == null) return null
        val encoded = Base64.getEncoder().encodeToString(source.url.toByteArray(Charsets.UTF_8))
        val resumeSeconds = (resumePositionMs.coerceIn(0, 86_400_000) / 1000L)
        return """
            <!doctype html>
            <html lang="zh"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
            <style>
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000;color:#fff;font-family:Roboto,"Noto Sans",sans-serif}
            #player{position:relative;width:100%;height:100%;height:100dvh;overflow:hidden;background:#000}
            video{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
            #gestures{position:absolute;inset:0;z-index:2;touch-action:none;user-select:none}
            #chrome{position:absolute;inset:0;z-index:3;pointer-events:none;opacity:1;transition:opacity .22s ease}
            #chrome.hidden{opacity:0}
            #chrome.hidden button,#chrome.hidden input{pointer-events:none}
            #chrome button,#chrome input{pointer-events:auto}
            #top,#bottom{position:absolute;left:0;right:0}
            #top{top:0;display:flex;align-items:center;gap:10px;padding:calc(8px + env(safe-area-inset-top)) 12px 24px;background:linear-gradient(to bottom,rgba(0,0,0,.83),transparent)}
            #title{font-size:14px;font-weight:600;flex:1;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
            #bottom{bottom:0;padding:26px 14px calc(14px + env(safe-area-inset-bottom));background:linear-gradient(to top,rgba(0,0,0,.88),transparent)}
            button{display:inline-flex;align-items:center;justify-content:center;border:0;background:transparent;color:#fff;font:inherit;cursor:pointer;user-select:none}
            button:active{transform:scale(.94)}
            .top-button{width:42px;height:42px;border-radius:50%;background:rgba(255,255,255,.12);font-size:24px}
            #center{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;gap:min(7vw,32px);pointer-events:none}
            #center button{pointer-events:auto;border-radius:50%;background:rgba(20,20,20,.55);backdrop-filter:blur(5px)}
            #center .skip{width:54px;height:54px;font-size:15px;font-weight:600}
            #play{width:68px;height:68px;font-size:30px}
            .time-row{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;gap:8px;font-size:12px;font-variant-numeric:tabular-nums}
            #progress{display:block;width:100%;height:22px;margin:0;appearance:none;-webkit-appearance:none;background:transparent;touch-action:none;cursor:pointer}
            #progress:disabled{opacity:.45}
            #progress::-webkit-slider-runnable-track{height:3px;background:rgba(255,255,255,.40);border-radius:3px}
            #progress::-webkit-slider-thumb{appearance:none;-webkit-appearance:none;width:11px;height:11px;margin-top:-4px;background:#fff;border-radius:50%;box-shadow:0 0 0 2px rgba(0,0,0,.35)}
            #progress::-moz-range-track{height:3px;background:rgba(255,255,255,.40);border-radius:3px}
            #progress::-moz-range-thumb{width:11px;height:11px;border:0;background:#fff;border-radius:50%}
            .actions{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:4px}
            .pill{height:37px;padding:0 12px;border-radius:19px;background:rgba(255,255,255,.14);font-size:13px;font-weight:550}
            #speed{font-variant-numeric:tabular-nums;min-width:58px}
            #hint{position:absolute;top:21%;left:10%;right:10%;z-index:4;text-align:center;pointer-events:none;opacity:0;transition:opacity .2s ease}
            #hint span{display:inline-block;max-width:100%;background:rgba(0,0,0,.7);padding:10px 14px;border-radius:14px;font-size:13px}
            #hint.show{opacity:1}
            #spinner{position:absolute;top:39%;left:50%;z-index:1;transform:translateX(-50%);border:3px solid #777;border-top-color:#fff;border-radius:50%;width:31px;height:31px;animation:spin .7s linear infinite}
            #spinner.hide{display:none}
            @keyframes spin{to{transform:translateX(-50%) rotate(360deg)}}
            @media(orientation:landscape){
              #top{padding:8px calc(10px + env(safe-area-inset-right)) 22px calc(10px + env(safe-area-inset-left))}
              #bottom{padding:18px calc(20px + env(safe-area-inset-right)) 9px calc(20px + env(safe-area-inset-left))}
              #play{width:64px;height:64px}#center .skip{width:50px;height:50px}
            }
            @media(max-width:350px){
              #center{gap:15px}#center .skip{width:48px;height:48px}
              #play{width:60px;height:60px}
            }
            </style>
            </head><body>
            <main id="player">
              <video id="video" playsinline webkit-playsinline preload="metadata"></video>
              <div id="spinner" aria-hidden="true"></div>
              <div id="gestures" aria-label="单击播放暂停，双击两侧快退快进十秒，长按两侧两倍速，上下区域左右滑动调整进度"></div>
              <div id="chrome">
                <div id="top">
                  <button class="top-button" id="back" aria-label="返回网站播放器">‹</button>
                  <span id="title">内置 HLS 播放器</span>
                  <button class="top-button" id="rotate" aria-label="横屏或竖屏">⛶</button>
                </div>
                <div id="center">
                  <button class="skip" id="back10" aria-label="快退10秒">↶10</button>
                  <button id="play" aria-label="播放或暂停">▶</button>
                  <button class="skip" id="forward10" aria-label="快进10秒">10↷</button>
                </div>
                <div id="bottom">
                  <div class="time-row">
                    <span id="current">00:00</span><span id="duration">--:--</span>
                  </div>
                  <input id="progress" aria-label="拖动调整播放进度" type="range"
                         min="0" max="1000" value="0" step="1" disabled>
                  <div class="actions">
                    <button id="speed" class="pill" aria-label="调整播放速度">1.0×</button>
                    <button id="lines" class="pill" aria-label="切换播放线路">⇄</button>
                    <span style="flex:1"></span>
                    <button id="rotateBottom" class="pill" aria-label="切换横屏">⛶ 全屏</button>
                  </div>
                </div>
              </div>
              <div id="hint" role="status"><span id="hintText"></span></div>
            </main>
            <script src="https://cdn.jsdelivr.net/npm/hls.js@1.6.15/dist/hls.min.js"></script>
            <script>
            (function(){
              'use strict';
              var video = document.getElementById('video');
              var mediaUrl = atob('$encoded');
              var pendingResumeSeconds = $resumeSeconds;
              var lastProgressReport = 0;
              var chrome = document.getElementById('chrome');
              var play = document.getElementById('play');
              var current = document.getElementById('current');
              var duration = document.getElementById('duration');
              var progress = document.getElementById('progress');
              var spinner = document.getElementById('spinner');
              var hint = document.getElementById('hint');
              var hintText = document.getElementById('hintText');
              var speed = document.getElementById('speed');
              var hideTimer = null, hintTimer = null, scrubbing = false;
              var speedList = [0.75,1,1.25,1.5,2], speedIndex = 1;
              function state(code){ console.log('SUPJAV_HLS_STATE:' + code); }
              function reportProgress(force){
                var now = Date.now();
                if (!force && now-lastProgressReport<2500) return;
                lastProgressReport = now;
                var at = Math.max(0, Math.floor((video.currentTime||0)*1000));
                var length = Number.isFinite(video.duration) && video.duration>0 ?
                  Math.floor(video.duration*1000) : 0;
                var playing = !video.paused && !video.ended ? 1 : 0;
                console.log('SUPJAV_HLS_PROGRESS:'+at+':'+length+':'+playing);
              }
              function applyResume(){
                if (pendingResumeSeconds<=0 || !Number.isFinite(video.duration)
                  || video.duration<=0) return;
                var target = Math.min(pendingResumeSeconds, video.duration-5);
                pendingResumeSeconds=0;
                if (target>0) {
                  try { video.currentTime=target; } catch(e) {}
                }
              }
              function clock(value){
                if(!isFinite(value) || value < 0) return '--:--';
                var v = Math.floor(value), sec = String(v%60).padStart(2,'0');
                var min = Math.floor(v/60);
                return min >= 60 ? Math.floor(min/60)+':'+String(min%60).padStart(2,'0')+':'+sec
                  : String(min).padStart(2,'0')+':'+sec;
              }
              function message(text){
                hintText.textContent = text;
                hint.classList.add('show');
                clearTimeout(hintTimer);
                hintTimer = setTimeout(function(){hint.classList.remove('show');},2100);
              }
              function chromeVisible(show){
                chrome.classList.toggle('hidden', !show);
                clearTimeout(hideTimer);
                if(show && !video.paused && !video.ended && !scrubbing){
                  hideTimer = setTimeout(function(){chrome.classList.add('hidden');},3500);
                }
              }
              function playbackIcon(){
                play.textContent = video.paused ? '▶' : '❚❚';
                spinner.classList.toggle('hide', !video.paused && video.readyState>=2);
              }
              function tick(){
                var valid = isFinite(video.duration) && video.duration > 0;
                progress.disabled = !valid;
                if(!scrubbing){
                  current.textContent = clock(video.currentTime);
                  progress.value = valid ? Math.floor(video.currentTime/video.duration*1000) : 0;
                }
                duration.textContent = valid ? clock(video.duration) : '--:--';
              }
              function toggle(){
                if(video.paused){video.play().catch(function(){message('点击播放按钮重试');state('tap-play');});}
                else video.pause();
                chromeVisible(true);
              }
              function skip(seconds){
                if(!isFinite(video.duration) || video.duration<=0){message('暂时无法快进');return;}
                var position = Math.max(0, Math.min(video.duration - 0.1, video.currentTime+seconds));
                video.currentTime=position;
                tick();
                message((seconds>0?'+':'')+seconds+' 秒');
                chromeVisible(true);
              }
              function rotate(){ state('rotate');chromeVisible(true); }
              document.getElementById('play').addEventListener('click',toggle);
              document.getElementById('back10').addEventListener('click',function(){skip(-10);});
              document.getElementById('forward10').addEventListener('click',function(){skip(10);});
              document.getElementById('back').addEventListener('click',function(){state('website');});
              document.getElementById('rotate').addEventListener('click',rotate);
              document.getElementById('rotateBottom').addEventListener('click',rotate);
              document.getElementById('lines').addEventListener('click',function(){
                state('lines');chromeVisible(true);
              });
              speed.addEventListener('click',function(){
                speedIndex=(speedIndex+1)%speedList.length;
                video.playbackRate=speedList[speedIndex];
                speed.textContent=speedList[speedIndex].toFixed(2).replace(/0+$/,'').replace(/\.$/,'')+'×';
                message('速度 '+speed.textContent);chromeVisible(true);
              });
              progress.addEventListener('input',function(){
                if(progress.disabled)return;
                scrubbing=true;
                current.textContent=clock(video.duration*Number(progress.value)/1000);
                chromeVisible(true);
              });
              progress.addEventListener('change',function(){
                if(!progress.disabled)video.currentTime=video.duration*Number(progress.value)/1000;
                scrubbing=false;tick();chromeVisible(true);
              });
              // A single pointer state machine avoids fighting with the seek
              // bar/buttons and defers single taps until the double-tap window
              // ends. Scrubbing only begins in the unused upper/lower video
              // regions, so the middle controls remain easy to operate.
              var gestureLayer = document.getElementById('gestures');
              var activeGesture = null, holdTimer = null, singleTapTimer = null;
              var lastTap = null, previousHoldRate = null;
              function gestureSide(x,width) {
                return x < width*.38 ? 'left' : x > width*.62 ? 'right' : 'center';
              }
              function restoreHold() {
                clearTimeout(holdTimer);
                holdTimer=null;
                if(previousHoldRate!==null) {
                  video.playbackRate=previousHoldRate;
                  previousHoldRate=null;
                  message('已恢复 '+video.playbackRate+'×');
                }
              }
              function clearGesture(){
                activeGesture=null;
                restoreHold();
              }
              gestureLayer.addEventListener('pointerdown',function(e){
                if(activeGesture || (e.pointerType==='mouse' && e.button!==0))return;
                var box=gestureLayer.getBoundingClientRect();
                var x=e.clientX-box.left,y=e.clientY-box.top;
                var side=gestureSide(x,box.width);
                activeGesture={id:e.pointerId,x:x,y:y,dx:0,dy:0,
                  width:box.width,height:box.height,side:side,
                  seeking:false,moved:false,holding:false,
                  startPosition:video.currentTime||0,preview:video.currentTime||0};
                try{gestureLayer.setPointerCapture(e.pointerId);}catch(err){}
                if(side!=='center'){
                  holdTimer=setTimeout(function(){
                    var g=activeGesture;
                    if(!g || g.moved || g.seeking || g.holding)return;
                    g.holding=true;
                    clearTimeout(singleTapTimer);singleTapTimer=null;lastTap=null;
                    previousHoldRate=video.playbackRate;
                    video.playbackRate=2;
                    if(video.paused)video.play().catch(function(){});
                    message('2× 倍速播放 · 松手恢复');
                    chromeVisible(true);
                  },420);
                }
              });
              gestureLayer.addEventListener('pointermove',function(e){
                var g=activeGesture;
                if(!g || e.pointerId!==g.id || g.holding)return;
                var box=gestureLayer.getBoundingClientRect();
                g.dx=(e.clientX-box.left)-g.x;
                g.dy=(e.clientY-box.top)-g.y;
                if(Math.abs(g.dx)>10 || Math.abs(g.dy)>10){
                  g.moved=true;
                  clearTimeout(holdTimer);holdTimer=null;
                }
                var emptyArea=g.y<g.height*.30 || g.y>g.height*.70;
                if(!g.seeking && emptyArea && Number.isFinite(video.duration) &&
                   video.duration>0 && Math.abs(g.dx)>12 &&
                   Math.abs(g.dx)>Math.abs(g.dy)*1.25){
                  g.seeking=true;
                  scrubbing=true;
                  clearTimeout(singleTapTimer);singleTapTimer=null;lastTap=null;
                }
                if(g.seeking){
                  e.preventDefault();
                  g.preview=Math.max(0,Math.min(video.duration,
                      g.startPosition+(g.dx/Math.max(g.width,1))*video.duration));
                  current.textContent=clock(g.preview);
                  progress.value=Math.round(g.preview/video.duration*1000);
                  message((g.dx>=0?'快进至 ':'快退至 ')+clock(g.preview));
                  chromeVisible(true);
                }
              });
              gestureLayer.addEventListener('pointerup',function(e){
                var g=activeGesture;
                if(!g || e.pointerId!==g.id)return;
                var wasHolding=g.holding,wasSeeking=g.seeking,wasMoved=g.moved;
                var target=g.preview,side=g.side;
                clearGesture();
                if(wasSeeking){
                  scrubbing=false;
                  if(Number.isFinite(video.duration)&&video.duration>0) {
                    video.currentTime=target;
                    reportProgress(true);
                  }
                  tick();chromeVisible(true);return;
                }
                if(wasHolding || wasMoved)return;
                var now=Date.now();
                if(lastTap && now-lastTap.time<=330 &&
                  lastTap.side===side && side!=='center' &&
                  Math.hypot(lastTap.x-g.x,lastTap.y-g.y)<42){
                  clearTimeout(singleTapTimer);singleTapTimer=null;lastTap=null;
                  skip(side==='left'?-10:10);
                  return;
                }
                clearTimeout(singleTapTimer);
                lastTap={time:now,side:side,x:g.x,y:g.y};
                singleTapTimer=setTimeout(function(){
                  lastTap=null;singleTapTimer=null;toggle();
                },330);
              });
              gestureLayer.addEventListener('pointercancel',function(){
                if(activeGesture && activeGesture.seeking){scrubbing=false;tick();}
                clearGesture();
              });
              window.addEventListener('blur',clearGesture);
              document.addEventListener('visibilitychange',function(){
                if(document.hidden)clearGesture();
              });
              video.addEventListener('play',function(){playbackIcon();chromeVisible(true);});
              video.addEventListener('pause',function(){playbackIcon();chromeVisible(true);reportProgress(true);});
              video.addEventListener('playing',function(){
                playbackIcon();spinner.classList.add('hide');state('playing');chromeVisible(true);reportProgress(true);
              });
              video.addEventListener('waiting',function(){
                spinner.classList.remove('hide');state('buffering');
              });
              video.addEventListener('seeking',function(){spinner.classList.remove('hide');});
              video.addEventListener('seeked',function(){playbackIcon();tick();reportProgress(true);});
              video.addEventListener('timeupdate',function(){tick();reportProgress(false);});
              video.addEventListener('durationchange',tick);
              video.addEventListener('loadedmetadata',function(){applyResume();tick();});
              video.addEventListener('ended',function(){playbackIcon();chromeVisible(true);reportProgress(true);});
              video.addEventListener('error',function(){spinner.classList.add('hide');state('video-error');message('视频播放失败');});
              document.addEventListener('visibilitychange',function(){
                if(!document.hidden)tick();
              });
              tick();playbackIcon();chromeVisible(true);
              try{
                if(window.Hls && Hls.isSupported()){
                  state('hlsjs');
                  var player=new Hls({enableWorker:false,maxBufferLength:20});
                  player.on(Hls.Events.MEDIA_ATTACHED,function(){player.loadSource(mediaUrl);});
                  player.on(Hls.Events.MANIFEST_PARSED,function(){
                    state('manifest');
                    video.play().catch(function(){state('tap-play');message('请点击播放');});
                  });
                  var networkRecoveries=0,mediaRecoveries=0;
                  player.on(Hls.Events.ERROR,function(event,error){
                    if(!error||!error.fatal)return;
                    var status=(error.response&&error.response.code)||0;
                    // Never repeat authentication, forbidden, or missing media.
                    // Transient connectivity and decode failures have small,
                    // bounded recovery budgets, not an endless retry loop.
                    if(error.type===Hls.ErrorTypes.NETWORK_ERROR &&
                       status!==401 && status!==403 && status!==404 &&
                       networkRecoveries<2){
                      networkRecoveries++;
                      state('network-retry');
                      message('网络中断，正在恢复播放…');
                      player.startLoad();
                      return;
                    }
                    if(error.type===Hls.ErrorTypes.MEDIA_ERROR && mediaRecoveries<1){
                      mediaRecoveries++;
                      state('media-recovery');
                      message('正在恢复视频解码…');
                      player.recoverMediaError();
                      return;
                    }
                    var code=status===403?'http-403':status===404?'http-404':'fatal-error';
                    state(code);spinner.classList.add('hide');
                    message(status===403?'媒体服务器拒绝播放 (403)':status===404?'播放地址已失效':'HLS 播放失败');
                    player.destroy();
                    player=null;
                  });
                  window.addEventListener('online',function(){
                    if(player && networkRecoveries<2 && !video.ended){
                      networkRecoveries++;
                      state('network-online');
                      player.startLoad();
                    }
                  });
                  player.attachMedia(video);
                }else if(video.canPlayType('application/vnd.apple.mpegurl')){
                  state('native-hls');video.src=mediaUrl;
                  video.play().catch(function(){state('tap-play');message('请点击播放');});
                }else{state('hlsjs-unavailable');message('HLS 播放器不可用');}
              }catch(e){state('player-exception');message('播放器初始化失败');}
            })();
            </script>
            </body></html>
        """.trimIndent()
    }
}
