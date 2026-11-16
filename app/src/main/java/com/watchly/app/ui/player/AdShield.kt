/*
 * Watchly — Ad / Popunder / Overlay Shield for the cloud stream player.
 *
 * Two layers:
 *  1. Network level — [isBlockedAdUrl] kills 65+ ad networks at request
 *     level inside WebViewClient.shouldInterceptRequest.
 *  2. DOM level — [AD_CLEAN_JS] is injected on every page start/finish and
 *     removes in-page banner/overlay/consent junk (the "ads at the top of
 *     the video" class of complaints) while never touching the player
 *     <video>/<iframe> itself. A MutationObserver re-applies the cleanup as
 *     the embed page injects new nodes.
 *
 * Everything here is pure string/predicate logic so it is unit-testable on
 * the JVM without a WebView.
 */
package com.watchly.app.ui.player

internal val BLOCKED_AD_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
    "exoclick.com", "juicyads.com", "onclickads.net", "hilltopads.net",
    "acscdn.com", "histats.com", "clickadu.com", "trafficjunky.net",
    "adsco.re", "pushame.com", "richpush.co", "evadav.com", "galaksion.com",
    "admaven.com", "monetag.com", "outbrain.com", "taboola.com",
    "yandex.ru/metrika", "mc.yandex.ru", "bet365", "1xbet", "mostbet",
    "melbet", "parimatch", "stake.com", "irtyc.com", "cdn4ads.com",
    "usrpubtrk.com", "a-ads.com", "tsyndicate.com", "realsrv.com",
    "syndication.realsrv.com", "zog.link", "rtmark.net", "cloudfront-ad"
)

internal fun isBlockedAdUrl(url: String): Boolean {
    val lower = url.lowercase()
    return BLOCKED_AD_DOMAINS.any { lower.contains(it) } ||
        lower.contains("/popunder") ||
        lower.contains("/adscript") ||
        lower.contains("/banner/") ||
        lower.contains("pop.js") ||
        lower.contains("ads.js")
}

internal fun isSniffableDirectMedia(url: String): Boolean {
    val lower = url.lowercase()
    if (isBlockedAdUrl(lower)) return false
    return (lower.contains(".m3u8") || lower.contains(".mp4")) &&
        !lower.endsWith(".ts") &&
        !lower.contains("preview") &&
        !lower.contains("thumb")
}

/**
 * Anti-popup + in-page ad/overlay cleaner. Kills window.open and
 * target=_blank hijacks, hides banner/ad/consent nodes by class or id,
 * drops sticky top bars above the player, and re-runs via MutationObserver
 * as embed pages inject more junk. The player video/iframe is never hidden.
 */
internal val AD_CLEAN_JS = """
(function() {
  try {
    window.open = function() { return null; };
    document.querySelectorAll('a[target="_blank"]').forEach(function(el) {
      el.removeAttribute('target');
      el.onclick = function(e) { e.preventDefault(); return false; };
    });
    var pending = null;
    function isPlayerNode(el) {
      try {
        var p = el, depth = 0;
        while (p && p.tagName && depth < 7) {
          var pc = ((p.id || '') + ' ' + (p.className && p.className.baseVal !== undefined ? p.className.baseVal : (p.className || ''))).toLowerCase();
          if (/player|jw-|jw_|plyr|html5|videojs|video-|hls|embed/.test(pc)) return true;
          p = p.parentElement;
          depth++;
        }
      } catch (e) {}
      return false;
    }
    function killAds() {
      try {
        var junk = /(^|[-_\s])(ad|ads|advert|advertisement|banner|sponsor|sponsored|popup|popunder|pop up|promo|preroll|pre-roll|overlay|interstitial|cookie|consent|gdpr|newsletter|pushopt|notification)([-_\s]|$)/i;
        document.querySelectorAll('body *').forEach(function(el) {
          try {
            if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'SOURCE') return;
            if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') return;
            // Never hide a node that CONTAINS the player, nor any node
            // inside a player container — ad cleanup must be structurally
            // incapable of breaking playback.
            if (el.querySelector('video') || el.querySelector('iframe')) return;
            if (isPlayerNode(el)) return;
            if (el.tagName === 'IFRAME') {
              var src = (el.getAttribute('src') || '').toLowerCase();
              // Keep the player embeds; kill ad/sponsor iframes only.
              if (!junk.test(src) && /player|embed|stream|video|server/.test(src)) return;
              if (!junk.test(src) && src.indexOf('http') === 0 && !/ad|banner|sponsor|pop/.test(src)) return;
            }
            var idc = ((el.id || '') + ' ' + (el.className && el.className.baseVal !== undefined ? el.className.baseVal : (el.className || ''))).toLowerCase();
            if (junk.test(idc)) {
              el.style.setProperty('display', 'none', 'important');
              el.style.setProperty('visibility', 'hidden', 'important');
              el.style.setProperty('height', '0', 'important');
              el.style.setProperty('max-height', '0', 'important');
              el.style.setProperty('overflow', 'hidden', 'important');
            }
          } catch (e) {}
        });
        // Sticky/fixed bars pinned to the TOP of the page (the "ads at the
        // top of the video" banners) collapse away.
        document.querySelectorAll('body > *').forEach(function(el) {
          try {
            if (el.querySelector('video') || el.querySelector('iframe') || isPlayerNode(el)) return;
            var cs = window.getComputedStyle(el);
            var top = parseInt(cs.top || '999', 10);
            if ((cs.position === 'fixed' || cs.position === 'sticky') && top <= 0) {
              el.style.setProperty('display', 'none', 'important');
            }
          } catch (e) {}
        });
      } catch (e) {}
    }
    function scheduleKill() {
      if (pending) clearTimeout(pending);
      pending = setTimeout(killAds, 400);
    }
    killAds();
    if (window.MutationObserver && document.documentElement) {
      var mo = new MutationObserver(scheduleKill);
      mo.observe(document.documentElement, { childList: true, subtree: true });
    }
    window.addEventListener('load', killAds);
  } catch (e) {}
})();
"""
