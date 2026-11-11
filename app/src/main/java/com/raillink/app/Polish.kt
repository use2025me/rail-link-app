package com.raillink.app

/** Small touches that make the website feel like a native app. */
object Polish {

    /**
     * - No grey/blue highlight box when tapping links and buttons
     * - No "copy / open in new tab" pop-up when long-pressing links and pictures
     *   (text boxes still allow long-press to paste)
     * - Tells the app whether the page is scrolled to the top, so pull-to-refresh
     *   only happens at the top of the page
     */
    const val SCRIPT = """
(function(){
  if (!document.getElementById('jp-polish')) {
    var st = document.createElement('style');
    st.id = 'jp-polish';
    st.textContent =
      '*{-webkit-tap-highlight-color:transparent!important}' +
      'a,img,video,button,[role=button]{-webkit-touch-callout:none!important;-webkit-user-select:none!important;user-select:none!important}' +
      'img{-webkit-user-drag:none!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  if (window.__jpScrollHook) return;
  window.__jpScrollHook = true;
  var last = null, queued = false;
  function report(){
    queued = false;
    var se = document.scrollingElement || document.documentElement;
    var top = se.scrollTop <= 0;
    var a = document.activeScroller;
    if (top && a && a.isConnected && a.scrollTop > 0) top = false;
    if (top !== last && window.JPBridge) { last = top; JPBridge.onScrollTop(top); }
  }
  document.addEventListener('scroll', function(e){
    var t = e.target;
    if (t && t.nodeType === 1 && t !== document.documentElement && t !== document.body &&
        t.scrollHeight > t.clientHeight + 4) document.activeScroller = t;
    if (!queued) { queued = true; requestAnimationFrame(report); }
  }, {capture: true, passive: true});
  report();
})();
"""
}
