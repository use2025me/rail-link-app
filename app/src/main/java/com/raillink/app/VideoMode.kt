package com.raillink.app

/**
 * Scripts for "landscape video mode" on phones: when the phone is turned sideways
 * while a video is playing, the video fills the whole screen and the website's
 * header and the phone's bars disappear.
 */
object VideoMode {

    /** Name the website uses to tell the app a video started playing. */
    const val BRIDGE = "JPBridge"

    /** Added to every page: tells the app when any video starts playing. */
    const val PLAY_HOOK = """
(function(){
  if (window.__jpPlayHook) return;
  window.__jpPlayHook = true;
  document.addEventListener('play', function(e){
    if (e.target && e.target.tagName === 'VIDEO' && window.JPBridge) JPBridge.onVideoPlay();
  }, true);
})();
"""

    /** Makes the playing video fill the screen. Returns "on" if a video was found. */
    const val ENTER = """
(function(){
  var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
  var v = vids.filter(function(x){ return !x.paused; })[0] ||
          vids.filter(function(x){ return x.currentTime > 0; })[0];
  var target = null;
  if (v) {
    target = v.closest('.video-js, .vjs-player, .plyr, [class*="player"], [class*="Player"]') || v;
  } else {
    target = Array.prototype.slice.call(document.querySelectorAll('iframe')).filter(function(f){
      return /player|video|vimeo|uscreen|wistia|youtube|embed/i.test(f.src || '') && f.offsetWidth > 200;
    })[0] || null;
  }
  if (!target) return 'none';
  var old = document.querySelectorAll('[data-jp-fs]');
  for (var i = 0; i < old.length; i++) old[i].removeAttribute('data-jp-fs');
  target.setAttribute('data-jp-fs', '1');
  var st = document.getElementById('jp-fs-style');
  if (!st) { st = document.createElement('style'); st.id = 'jp-fs-style'; document.head.appendChild(st); }
  st.textContent =
    '[data-jp-fs]{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;' +
    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
    'margin:0!important;padding:0!important;z-index:2147483647!important;background:#000!important;' +
    'border-radius:0!important;transform:none!important}' +
    '[data-jp-fs] video,video[data-jp-fs]{width:100%!important;height:100%!important;object-fit:contain!important}' +
    'html,body{overflow:hidden!important}';
  return 'on';
})();
"""

    /** Puts the page back to normal. */
    const val EXIT = """
(function(){
  var st = document.getElementById('jp-fs-style');
  if (st) st.remove();
  var old = document.querySelectorAll('[data-jp-fs]');
  for (var i = 0; i < old.length; i++) old[i].removeAttribute('data-jp-fs');
  return 'off';
})();
"""
}
