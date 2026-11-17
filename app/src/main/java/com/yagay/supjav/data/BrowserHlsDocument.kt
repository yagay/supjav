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
    fun render(
        source: PlaybackSource, resumePositionMs: Long = 0L,
        qualityPreference: String = "highest",
        defaultSpeed: Float = 1f,
        defaultFill: Boolean = false,
        skipSeconds: Int = 10,
        holdSpeed: Float = 2f,
        autoPlay: Boolean = true,
        initialBrightness: Float = 0.5f,
        initialVolume: Float = 0.5f,
        availableParts: List<String> = emptyList(),
        activePart: String? = null
    ): String? {
        if (source.mimeType != "application/x-mpegURL" ||
            MediaSourceDetector.origin(source.url) == null ||
            MediaSourceDetector.origin(source.referer) == null) return null
        val encoded = Base64.getEncoder().encodeToString(source.url.toByteArray(Charsets.UTF_8))
        val resumeSeconds = (resumePositionMs.coerceIn(0, 86_400_000) / 1000L)
        val qualityToken = com.yagay.supjav.player.VideoQualityPolicy.normalize(qualityPreference)
        val playbackSpeed = com.yagay.supjav.player.PlaybackDefaults.speed(defaultSpeed)
        val playbackHold = com.yagay.supjav.player.PlaybackDefaults.holdSpeed(holdSpeed)
        val playbackSkip = com.yagay.supjav.player.PlaybackDefaults.skip(skipSeconds)
        val playbackFit = if (defaultFill) "cover" else "contain"
        val playbackAuto = if (autoPlay) "true" else "false"
        val brightnessStart = if (initialBrightness.isFinite())
            initialBrightness.coerceIn(0.01f, 1f) else 0.5f
        val volumeStart = if (initialVolume.isFinite())
            initialVolume.coerceIn(0f, 1f) else 0.5f
        // Only render validated part labels; data-link/media URLs never enter
        // the controls HTML or the bridge's event payload.
        val parts = availableParts.distinct()
            .filter { Regex("""^CD[1-9][0-9]?$""").matches(it) }.take(10)
        val partMarkup = if (parts.size > 1) {
            val buttons = parts.joinToString("") { part ->
                val selected = part == activePart
                "<button class=\"cd-button" +
                    (if (selected) " selected" else "") +
                    "\" data-part=\"" + part + "\"" +
                    (if (selected) " disabled" else "") + ">" +
                    part + "</button>"
            }
            "<div id=\"cdRow\" aria-label=\"选择视频分段\">" +
                "<span>分段</span>" + buttons + "</div>"
        } else ""
        return """
            <!doctype html>
            <html lang="zh"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
            <style>
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000;color:#fff;font-family:Roboto,"Noto Sans",sans-serif}
            #player{position:relative;width:100%;height:100%;height:100dvh;overflow:hidden;background:#000}
            #player.swiping #spinner,#player.swiping #pausedIndicator,#player.swiping #playFlash,#player.swiping #gestureFeedback,#player.swiping #hint{display:none!important}
            video{position:absolute;inset:0;width:100%;height:100%;object-fit:$playbackFit;background:#000}
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
            #gestureSeek{position:absolute;left:12px;right:12px;bottom:calc(24px + env(safe-area-inset-bottom));z-index:7;padding:12px 14px;border-radius:12px;background:rgba(0,0,0,.68);pointer-events:none}
            #gestureSeekTime{display:flex;justify-content:space-between;font-size:12px;margin-bottom:8px;font-variant-numeric:tabular-nums}
            #gestureSeekTrack{height:3px;border-radius:3px;background:rgba(255,255,255,.34);overflow:hidden}
            #gestureSeekFill{height:100%;width:0%;background:white}
            #gestureLevel{position:absolute;z-index:7;top:44%;transform:translateY(-50%);width:96px;padding:10px 12px;border-radius:12px;background:rgba(0,0,0,.64);text-align:center;font-size:13px;pointer-events:none}
            #gestureLevel.left{left:12px}#gestureLevel.right{right:12px}
            #gestureLevelTrack{height:3px;margin-top:8px;background:rgba(255,255,255,.34);border-radius:3px;overflow:hidden}
            #gestureLevelFill{height:100%;background:white;width:0%}
            #pausedIndicator{position:absolute;z-index:4;left:50%;top:50%;transform:translate(-50%,-50%);width:52px;height:52px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.30);font-size:29px;color:#fff;pointer-events:none}
            #center .skip{width:54px;height:54px;font-size:15px;font-weight:600}
            #play{width:68px;height:68px;font-size:30px}
            .time-row{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;gap:8px;font-size:12px;font-variant-numeric:tabular-nums}
            #progress{display:block;width:100%;height:22px;margin:0;appearance:none;-webkit-appearance:none;background:transparent;touch-action:none;cursor:pointer}
            #progress:disabled{opacity:.45}
            #progress::-webkit-slider-runnable-track{height:3px;background:linear-gradient(to right,#fff 0%,#fff var(--seek-progress,0%),rgba(255,255,255,.40) var(--seek-progress,0%),rgba(255,255,255,.40) 100%);border-radius:3px}
            #progress::-webkit-slider-thumb{appearance:none;-webkit-appearance:none;width:11px;height:11px;margin-top:-4px;background:#fff;border-radius:50%;box-shadow:0 0 0 2px rgba(0,0,0,.35)}
            #progress::-moz-range-track{height:3px;background:rgba(255,255,255,.40);border-radius:3px}
            #progress::-moz-range-thumb{width:11px;height:11px;border:0;background:#fff;border-radius:50%}
            .actions{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));align-items:center;gap:6px;margin-top:4px}
            .pill{height:37px;min-width:0;width:100%;padding:0 3px;border-radius:19px;background:rgba(255,255,255,.14);font-size:12px;font-weight:550;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            #speed{font-variant-numeric:tabular-nums}
            #cdRow{display:flex;align-items:center;gap:4px;overflow-x:auto;white-space:nowrap;padding:0 0 3px;scrollbar-width:none}
            #cdRow span{font-size:11px;color:rgba(255,255,255,.77);margin-right:0}
            #cdRow .cd-button{flex:0 0 auto;min-height:26px;min-width:0;padding:0 8px;border-radius:7px;background:rgba(255,255,255,.15);font-size:11px;font-weight:600}
            #cdRow .cd-button.selected{background:rgba(255,255,255,.35);color:#fff;opacity:1}
            @media(max-width:350px){.actions{grid-template-columns:repeat(4,minmax(0,1fr))}}
            [hidden]{display:none!important}
            #qualityMenu{position:absolute;right:12px;bottom:calc(73px + env(safe-area-inset-bottom));z-index:6;max-height:min(48vh,280px);overflow:auto;display:flex;flex-direction:column;gap:3px;min-width:116px;border-radius:12px;padding:6px;background:rgba(25,25,25,.96);box-shadow:0 4px 20px rgba(0,0,0,.4)}
            #qualityMenu button{min-height:36px;justify-content:flex-start;padding:6px 12px;border-radius:8px;font-size:13px}
            #qualityMenu button.selected{background:rgba(255,255,255,.18)}
            #hint{position:absolute;top:21%;left:10%;right:10%;z-index:4;text-align:center;pointer-events:none;opacity:0;transition:opacity .2s ease}
            #hint span{display:inline-block;max-width:100%;background:rgba(0,0,0,.7);padding:10px 14px;border-radius:14px;font-size:13px}
            #hint.show{opacity:1}
            /* Feedback stays small and out of the middle of the picture. */
            #gestureFeedback{position:absolute;z-index:5;left:50%;top:46%;transform:translate(-50%,-50%);pointer-events:none;opacity:0;transition:opacity .14s ease;border-radius:18px;background:rgba(0,0,0,.27);padding:6px 10px;font-size:14px;font-weight:650;text-shadow:0 1px 2px rgba(0,0,0,.65);white-space:nowrap}
            #gestureFeedback.show{opacity:1}
            #gestureFeedback.left{left:16%}
            #gestureFeedback.right{left:84%}
            #gestureFeedback.boost{top:24%;font-size:13px;padding:5px 9px}
            #playFlash{position:absolute;z-index:5;left:50%;top:50%;transform:translate(-50%,-50%);pointer-events:none;opacity:0;transition:opacity .16s ease;display:flex;align-items:center;justify-content:center;width:48px;height:48px;border-radius:50%;background:rgba(0,0,0,.23);font-size:24px;color:rgba(255,255,255,.92)}
            #playFlash.show{opacity:1}
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
              <div id="gestures" aria-label="首次单击显示控制栏，再次单击暂停，双击两侧跳转，长按两侧倍速，上下区域左右滑动进度"></div>
              <div id="pausedIndicator" aria-label="已暂停" hidden>❚❚</div>
              <div id="gestureSeek" aria-label="正在调整播放进度" hidden>
                <div id="gestureSeekTime"><span id="gestureSeekCurrent">00:00</span><span id="gestureSeekTotal">--:--</span></div>
                <div id="gestureSeekTrack"><div id="gestureSeekFill"></div></div>
              </div>
              <div id="gestureLevel" role="status" hidden>
                <span id="gestureLevelText"></span>
                <div id="gestureLevelTrack"><div id="gestureLevelFill"></div></div>
              </div>
              <div id="chrome">
                <div id="top">
                  <button class="top-button" id="back" aria-label="返回网站播放器">‹</button>
                  <span id="title">内置 HLS 播放器</span>
                  <button class="top-button" id="playlist" aria-label="打开播放列表" style="font-size:20px">☷</button>
                  <button class="top-button" id="rotate" aria-label="横屏或竖屏">⛶</button>
                </div>
                <div id="center">
                  <button class="skip" id="back10" aria-label="快退${playbackSkip}秒">↶$playbackSkip</button>
                  <button id="play" aria-label="播放或暂停">▶</button>
                  <button class="skip" id="forward10" aria-label="快进${playbackSkip}秒">$playbackSkip↷</button>
                </div>
                <div id="bottom">
                  $partMarkup
                  <div class="time-row">
                    <span id="current">00:00</span><span id="duration">--:--</span>
                  </div>
                  <input id="progress" aria-label="拖动调整播放进度" type="range"
                         min="0" max="1000" value="0" step="1" disabled>
                  <div class="actions">
                    <button id="speed" class="pill" aria-label="调整播放速度">1.0×</button>
                    <button id="aspect" class="pill" aria-label="切换画面比例">比例</button>
                    <button id="lines" class="pill" aria-label="切换播放线路">⇄</button>
                    <button id="quality" class="pill" aria-label="切换视频画质" hidden>HD</button>
                    <button id="rotateBottom" class="pill" aria-label="切换横屏">⛶</button>
                  </div>
                </div>
              </div>
              <div id="qualityMenu" role="menu" aria-label="画质选项" hidden></div>
              <div id="hint" role="status"><span id="hintText"></span></div>
              <div id="gestureFeedback" aria-hidden="true"></div>
              <div id="playFlash" aria-hidden="true"></div>
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
              var gestureFeedback = document.getElementById('gestureFeedback');
              var playFlash = document.getElementById('playFlash');
              var gestureSeek = document.getElementById('gestureSeek');
              var gestureSeekCurrent = document.getElementById('gestureSeekCurrent');
              var gestureSeekTotal = document.getElementById('gestureSeekTotal');
              var gestureSeekFill = document.getElementById('gestureSeekFill');
              var gestureLevel = document.getElementById('gestureLevel');
              var gestureLevelText = document.getElementById('gestureLevelText');
              var gestureLevelFill = document.getElementById('gestureLevelFill');
              var brightnessValue = $brightnessStart;
              var volumeValue = $volumeStart;
              var pausedIndicator = document.getElementById('pausedIndicator');
              var gestureFeedbackTimer = null, playFlashTimer = null;
              var speed = document.getElementById('speed');
              var aspect = document.getElementById('aspect');
              var seekAmount = $playbackSkip;
              var boostRate = $playbackHold;
              var autoStart = $playbackAuto;
              var defaultRate = $playbackSpeed;
              var quality = document.getElementById('quality');
              var qualityMenu = document.getElementById('qualityMenu');
              var qualityMode = '$qualityToken';
              var qualityLevels = [];
              var player = null;
              var hideTimer = null, hintTimer = null, scrubbing = false;
              var speedList = [0.75,1,1.25,1.5,2], speedIndex = 1;
              speedIndex = speedList.indexOf(defaultRate);
              if(speedIndex<0)speedIndex=1;
              video.playbackRate=defaultRate;
              speed.textContent=defaultRate+'×';
              video.style.objectFit='$playbackFit';
              aspect.textContent=video.style.objectFit==='cover'?'铺满':'适应';
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
              function subtleFeedback(label,side,hold){
                clearTimeout(gestureFeedbackTimer);
                gestureFeedback.textContent=label;
                gestureFeedback.className='show'+(side==='left'?' left':side==='right'?' right':'')+(hold?' boost':'');
                if(!hold)gestureFeedbackTimer=setTimeout(function(){
                  gestureFeedback.className='';
                },620);
              }
              function clearSubtleFeedback(){
                clearTimeout(gestureFeedbackTimer);
                gestureFeedback.className='';
              }
              function playbackFlash(willPlay){
                clearTimeout(playFlashTimer);
                playFlash.textContent=willPlay?'▶':'❚❚';
                playFlash.classList.add('show');
                playFlashTimer=setTimeout(function(){playFlash.classList.remove('show');},480);
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
                pausedIndicator.hidden = !video.paused ||
                  (video.readyState<2 && autoStart) || video.ended;
                spinner.classList.toggle('hide', !video.paused && video.readyState>=2);
              }
              function paintProgress(){
                progress.style.setProperty('--seek-progress',
                  (Number(progress.value)/10).toFixed(1)+'%');
              }
              function tick(){
                var valid = isFinite(video.duration) && video.duration > 0;
                progress.disabled = !valid;
                if(!scrubbing){
                  current.textContent = clock(video.currentTime);
                  progress.value = valid ? Math.floor(video.currentTime/video.duration*1000) : 0;
                }
                duration.textContent = valid ? clock(video.duration) : '--:--';
                paintProgress();
              }
              function toggle(){
                // Explicit transport play/pause button always acts immediately.
                var willPlay=video.paused;
                playbackFlash(willPlay);
                if(willPlay){video.play().catch(function(){message('点击播放按钮重试');state('tap-play');});}
                else video.pause();
                chromeVisible(true);
              }
              function onSurfaceTap(){
                if(video.paused){toggle();return;}
                if(chrome.classList.contains('hidden')){
                  // First tap only reveals icons and the seek bar.
                  chromeVisible(true);
                  return;
                }
                // A second tap while controls are visible pauses.
                toggle();
              }
              function skip(seconds){
                if(!isFinite(video.duration) || video.duration<=0){message('暂时无法快进');return;}
                var position = Math.max(0, Math.min(video.duration - 0.1, video.currentTime+seconds));
                video.currentTime=position;
                tick();
                subtleFeedback((seconds>0?'+':'−')+Math.abs(seconds)+'s',
                  seconds>0?'right':'left',false);
              }
              function rotate(){ state('rotate');chromeVisible(true); }
              document.getElementById('play').addEventListener('click',toggle);
              document.getElementById('back10').addEventListener('click',function(){skip(-seekAmount);});
              document.getElementById('forward10').addEventListener('click',function(){skip(seekAmount);});
              document.getElementById('back').addEventListener('click',function(){state('website');});
              document.getElementById('rotate').addEventListener('click',rotate);
              document.getElementById('rotateBottom').addEventListener('click',rotate);
              document.getElementById('lines').addEventListener('click',function(){
                state('lines');chromeVisible(true);
              });
              document.getElementById('playlist').addEventListener('click',function(){
                state('playlist');chromeVisible(true);
              });
              document.querySelectorAll('#cdRow button[data-part]').forEach(function(button){
                button.addEventListener('click',function(){
                  var part=button.getAttribute('data-part');
                  if(!button.disabled && /^CD[1-9][0-9]?$/.test(part)){
                    state('part:'+part);chromeVisible(true);
                  }
                });
              });
              // Use only manifest-declared renditions, never infer a resolution
              // from the EVS/LUC provider's hls2/hls3/hls4 URL tokens.
              function qualityHeight(level){
                return level && Number.isFinite(level.height) && level.height>=144 &&
                    level.height<=4320 ? Math.round(level.height) : 0;
              }
              function availableQualityLevels(){
                if(!player || !Array.isArray(player.levels))return [];
                return player.levels.map(function(level,index){
                  return {index:index,height:qualityHeight(level),
                    bitrate:level.bitrate||0};
                }).filter(function(level){return level.height>0;});
              }
              function selectQualityLevel(mode){
                if(!player)return;
                if(mode==='auto'){player.nextLevel=-1;return;}
                var entries=availableQualityLevels();
                if(!entries.length)return;
                var heights=entries.map(function(e){return e.height;});
                var target;
                if(mode==='highest')target=Math.max.apply(null,heights);
                else{
                  var requested=Number(mode.substring(1));
                  if(!/^p[1-9][0-9]{2,3}$/.test(mode) || requested>4320)return;
                  var below=heights.filter(function(h){return h<=requested;});
                  target=below.length?Math.max.apply(null,below):
                    Math.min.apply(null,heights);
                }
                var candidates=entries.filter(function(e){return e.height===target;});
                candidates.sort(function(a,b){return b.bitrate-a.bitrate;});
                if(candidates.length)player.nextLevel=candidates[0].index;
              }
              function paintQualityLabel(){
                var level=player&&player.levels ? player.levels[player.currentLevel] : null;
                var suffix=qualityHeight(level);
                quality.textContent=(qualityMode==='auto'?'自动':
                  qualityMode==='highest'?'最高':qualityMode.substring(1)+'p')+
                  (qualityMode==='auto' && suffix?' · '+suffix+'p':'');
              }
              function renderQualityMenu(){
                qualityLevels=availableQualityLevels();
                var heights=Array.from(new Set(qualityLevels.map(function(e){
                  return e.height;
                }))).sort(function(a,b){return b-a;});
                quality.hidden=heights.length<=1;
                qualityMenu.hidden=true;
                qualityMenu.textContent='';
                if(heights.length<=1)return;
                var modes=['auto','highest'].concat(heights.map(function(h){return 'p'+h;}));
                modes.forEach(function(mode){
                  var b=document.createElement('button');
                  b.type='button';
                  b.className=mode===qualityMode?'selected':'';
                  b.textContent=(mode===qualityMode?'✓ ':'')+
                    (mode==='auto'?'自动':mode==='highest'?'最高可用':mode.substring(1)+'p');
                  b.addEventListener('click',function(){
                    qualityMode=mode;
                    selectQualityLevel(mode);
                    qualityMenu.hidden=true;
                    state('quality:'+mode);
                    paintQualityLabel();
                    chromeVisible(true);
                  });
                  qualityMenu.appendChild(b);
                });
                paintQualityLabel();
              }
              quality.addEventListener('click',function(){
                if(quality.hidden)return;
                renderQualityMenu();
                qualityMenu.hidden=false;
                chromeVisible(true);
              });
              aspect.textContent='$playbackFit'==='cover'?'铺满':'适应';
              aspect.addEventListener('click',function(){
                video.style.objectFit=video.style.objectFit==='cover'?'contain':'cover';
                aspect.textContent=video.style.objectFit==='cover'?'铺满':'适应';
                chromeVisible(true);
              });
              speed.addEventListener('click',function(){
                speedIndex=(speedIndex+1)%speedList.length;
                video.playbackRate=speedList[speedIndex];
                speed.textContent=speedList[speedIndex].toFixed(2).replace(/0+$/,'').replace(/\.$/,'')+'×';
                subtleFeedback(speed.textContent,'right',false);chromeVisible(true);
              });
              progress.addEventListener('input',function(){
                if(progress.disabled)return;
                scrubbing=true;
                current.textContent=clock(video.duration*Number(progress.value)/1000);
                paintProgress();
                chromeVisible(true);
              });
              progress.addEventListener('change',function(){
                if(!progress.disabled)video.currentTime=video.duration*Number(progress.value)/1000;
                scrubbing=false;tick();chromeVisible(true);
              });
              // Axis-locking pointer state machine. Swipes work over the
              // entire player when controls are hidden; drags never reveal
              // transport buttons. One seek is committed on finger release.
              var gestureLayer = document.getElementById('gestures');
              var activeGesture = null, holdTimer = null, singleTapTimer = null;
              var lastTap = null, previousHoldRate = null, holdWasPaused = false;
              function gestureSide(x,width) {
                return x < width*.38 ? 'left' : x > width*.62 ? 'right' : 'center';
              }
              function clampLevel(v){return Math.max(0,Math.min(1,v));}
              function updateGestureSeek(seconds){
                var valid=Number.isFinite(video.duration) && video.duration>0;
                if(!valid)return;
                gestureSeekCurrent.textContent=clock(seconds);
                gestureSeekTotal.textContent=clock(video.duration);
                gestureSeekFill.style.width=(100*seconds/video.duration).toFixed(1)+'%';
                gestureSeek.hidden=false;
              }
              function updateGestureLevel(kind,value) {
                var safe=clampLevel(value);
                if(kind==='brightness')brightnessValue=safe;
                else volumeValue=safe;
                gestureLevelText.textContent=(kind==='brightness'?'☀ 亮度':'♪ 音量')+
                  ' '+Math.round(safe*100)+'%';
                gestureLevelFill.style.width=Math.round(safe*100)+'%';
                gestureLevel.className=kind==='brightness'?'left':'right';
                gestureLevel.hidden=false;
                var now=Date.now(),g=activeGesture;
                if(g && (!g.lastLevelReport || now-g.lastLevelReport>35 ||
                    Math.abs(safe-g.lastReportedValue)>.045)) {
                  g.lastLevelReport=now;g.lastReportedValue=safe;
                  console.log('SUPJAV_HLS_LEVEL:'+kind+':'+safe.toFixed(3));
                }
              }
              function restoreHold() {
                clearTimeout(holdTimer);holdTimer=null;
                if(previousHoldRate!==null) {
                  video.playbackRate=previousHoldRate;
                  previousHoldRate=null;
                  if(holdWasPaused)video.pause();
                  holdWasPaused=false;
                  clearSubtleFeedback();
                }
              }
              function clearGesture(){
                if(activeGesture && activeGesture.mode==='seek'){
                  scrubbing=false;tick();
                }
                gestureSeek.hidden=true;
                gestureLevel.hidden=true;
                document.getElementById('player').classList.remove('swiping');
                activeGesture=null;
                restoreHold();
                playbackIcon();
              }
              gestureLayer.addEventListener('pointerdown',function(e){
                if(activeGesture || (e.pointerType==='mouse' && e.button!==0))return;
                var box=gestureLayer.getBoundingClientRect();
                var x=e.clientX-box.left,y=e.clientY-box.top;
                var side=gestureSide(x,box.width);
                if(lastTap && side===lastTap.side && Date.now()-lastTap.time<=330){
                  clearTimeout(singleTapTimer);singleTapTimer=null;
                }
                activeGesture={id:e.pointerId,x:x,y:y,dx:0,dy:0,
                  width:box.width,height:box.height,side:side,
                  mode:null,seeking:false,moved:false,holding:false,
                  canSeek:chrome.classList.contains('hidden') ||
                    y<box.height*.30 || y>box.height*.70,
                  startPosition:video.currentTime||0,preview:video.currentTime||0,
                  startLevel:x<box.width/2?brightnessValue:volumeValue,
                  lastLevelReport:0,lastReportedValue:-1};
                try{gestureLayer.setPointerCapture(e.pointerId);}catch(err){}
                if(side!=='center'){
                  holdTimer=setTimeout(function(){
                    var g=activeGesture;
                    if(!g || g.moved || g.mode || g.holding)return;
                    g.holding=true;
                    clearTimeout(singleTapTimer);singleTapTimer=null;lastTap=null;
                    previousHoldRate=video.playbackRate;
                    holdWasPaused=video.paused;
                    video.playbackRate=boostRate;
                    if(video.paused)video.play().catch(function(){});
                    subtleFeedback(boostRate+'×',side,true);
                  },420);
                }
              });
              gestureLayer.addEventListener('pointermove',function(e){
                var g=activeGesture;
                if(!g || e.pointerId!==g.id || g.holding)return;
                g.dx=e.clientX-gestureLayer.getBoundingClientRect().left-g.x;
                g.dy=e.clientY-gestureLayer.getBoundingClientRect().top-g.y;
                if(Math.abs(g.dx)>10 || Math.abs(g.dy)>10){
                  g.moved=true;clearTimeout(holdTimer);holdTimer=null;
                }
                if(!g.mode && g.moved) {
                  if(g.canSeek && Number.isFinite(video.duration) &&
                     video.duration>0 && Math.abs(g.dx)>Math.abs(g.dy)*1.25){
                    g.mode='seek';g.seeking=true;scrubbing=true;
                  } else if(Math.abs(g.dy)>Math.abs(g.dx)*1.25){
                    g.mode=g.x<g.width/2?'brightness':'volume';
                  }
                  if(g.mode){
                    clearTimeout(singleTapTimer);singleTapTimer=null;lastTap=null;
                    document.getElementById('player').classList.add('swiping');
                    chromeVisible(false);
                  }
                }
                if(g.mode==='seek'){
                  e.preventDefault();
                  g.preview=Math.max(0,Math.min(video.duration,
                      g.startPosition+(g.dx/Math.max(g.width,1))*video.duration));
                  updateGestureSeek(g.preview);
                } else if(g.mode==='brightness'||g.mode==='volume'){
                  e.preventDefault();
                  updateGestureLevel(g.mode,
                    g.startLevel-(g.dy/Math.max(g.height,1))*1.25);
                }
              });
              gestureLayer.addEventListener('pointerup',function(e){
                var g=activeGesture;
                if(!g || e.pointerId!==g.id)return;
                var mode=g.mode,holding=g.holding,moved=g.moved;
                var target=g.preview,side=g.side;
                if(mode==='brightness'||mode==='volume'){
                  // Deliver the final exact level, not just the throttled one.
                  var value=mode==='brightness'?brightnessValue:volumeValue;
                  console.log('SUPJAV_HLS_LEVEL:'+mode+':'+value.toFixed(3));
                }
                clearGesture();
                if(mode==='seek'){
                  if(Number.isFinite(video.duration)&&video.duration>0){
                    video.currentTime=target;reportProgress(true);
                  }
                  tick();return;
                }
                if(mode || holding || moved)return;
                var now=Date.now();
                if(lastTap && now-lastTap.time<=330 &&
                   lastTap.side===side && side!=='center' &&
                   Math.hypot(lastTap.x-g.x,lastTap.y-g.y)<42){
                  clearTimeout(singleTapTimer);singleTapTimer=null;lastTap=null;
                  skip(side==='left'?-seekAmount:seekAmount);
                  return;
                }
                clearTimeout(singleTapTimer);
                lastTap={time:now,side:side,x:g.x,y:g.y};
                singleTapTimer=setTimeout(function(){
                  lastTap=null;singleTapTimer=null;onSurfaceTap();
                },330);
              });
              gestureLayer.addEventListener('pointercancel',function(){
                clearTimeout(singleTapTimer);singleTapTimer=null;lastTap=null;
                clearGesture();
              });
              window.addEventListener('blur',clearGesture);
              document.addEventListener('visibilitychange',function(){
                if(document.hidden)clearGesture();
              });
              video.addEventListener('play',function(){
                playbackIcon();
                if(chrome.classList.contains('hidden') ||
                   (activeGesture && activeGesture.holding))chromeVisible(false);
                else chromeVisible(true);
              });
              video.addEventListener('pause',function(){playbackIcon();chromeVisible(true);reportProgress(true);});
              video.addEventListener('playing',function(){
                playbackIcon();spinner.classList.add('hide');state('playing');
                if(chrome.classList.contains('hidden'))chromeVisible(false);
                else chromeVisible(true);
                reportProgress(true);
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
              tick();playbackIcon();chromeVisible(!autoStart);
              try{
                if(window.Hls && Hls.isSupported()){
                  state('hlsjs');
                  player=new Hls({enableWorker:false,maxBufferLength:20});
                  player.on(Hls.Events.MEDIA_ATTACHED,function(){player.loadSource(mediaUrl);});
                  player.on(Hls.Events.MANIFEST_PARSED,function(){
                    state('manifest');
                    renderQualityMenu();
                    selectQualityLevel(qualityMode);
                    if(autoStart)video.play().catch(function(){state('tap-play');message('请点击播放');});
                  });
                  player.on(Hls.Events.LEVEL_SWITCHED,function(){paintQualityLabel();});
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
                  if(autoStart)video.play().catch(function(){state('tap-play');message('请点击播放');});
                }else{state('hlsjs-unavailable');message('HLS 播放器不可用');}
              }catch(e){state('player-exception');message('播放器初始化失败');}
            })();
            </script>
            </body></html>
        """.trimIndent()
    }
}
