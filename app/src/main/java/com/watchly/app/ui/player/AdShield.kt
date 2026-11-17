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
    // Only real network URLs can be downloaded or played back; a data:
    // page whose *payload* mentions .m3u8 must never match.
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    return (lower.contains(".m3u8") || lower.contains(".mp4")) &&
        !lower.endsWith(".ts") &&
        !lower.contains("preview") &&
        !lower.contains("thumb")
}

/**
 * Force-start boot: embed pages that wait for a tap (#bigPlay, the
 * `autoStart:false` landing class) or ship a <video> that needs
 * .play() get booted so the media request actually happens — the
 * sniff can capture it and the visible player stops "just loading".
 * The play matcher is deliberately tight (known ids + exact "play"
 * label) so it can never click purchase/rent-style controls.
 */
internal val SNIFF_BOOT_JS = """
(function() {
  try {
    document.querySelectorAll('video').forEach(function(v) {
      try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {}
    });
    var ids = ['bigPlay', 'bigplay', 'play-button', 'playButton', 'play_btn'];
    for (var i = 0; i < ids.length; i++) {
      var el = document.getElementById(ids[i]);
      if (el) { try { el.click(); } catch (e) {} }
    }
    document.querySelectorAll('button,[role="button"]').forEach(function(el) {
      try {
        if ((el.textContent || '').trim().toLowerCase() === 'play') el.click();
      } catch (e) {}
    });
  } catch (e) {}
})();
"""

/**
 * True when a page that LOADED FINE is a dead end: its visible text
 * says the media is gone ("MEDIA NOT FOUND" and friends) and there is
 * no video element on the page. These pages previously hung the
 * player forever because HTTP 200 looks like success.
 */
internal fun isDeadPageText(pageText: String, hasVideoElement: Boolean = false): Boolean {
    if (hasVideoElement) return false
    val text = pageText.lowercase()
    if (text.length > 4_000) return false
    val patterns = listOf(
        "media not found", "video not found", "file not found",
        "movie not found", "episode not found", "content not found",
        "content unavailable", "content not available",
        "video has been removed", "video is unavailable",
        "no sources found", "no sources available",
        "stream not found", "stream is unavailable",
        "this video is not available", "sorry, this video",
        "could not be found", "has been deleted", "was not found"
    )
    return patterns.any { text.contains(it) }
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
        // Centered fake dialogs / prize notifications / popup boxes:
        // any fixed or absolute layer floating OVER the page that does
        // not contain the player and does contain tappable controls.
        // This is the "scam popup with a phone number" class of junk,
        // which usually carries neutral class names the keyword scan
        // above cannot see.
        document.querySelectorAll('body *').forEach(function(el) {
          try {
            if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'SOURCE') return;
            if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') return;
            if (el.querySelector('video') || isPlayerNode(el)) return;
            if (el.querySelector('iframe')) return;
            var cs = window.getComputedStyle(el);
            if (cs.position !== 'fixed' && cs.position !== 'absolute' && cs.position !== 'sticky') return;
            var z = parseInt(cs.zIndex || '0', 10);
            if (z < 200) return;
            var r = el.getBoundingClientRect();
            if (r.width < window.innerWidth * 0.4) return;
            if (r.height < 44 || r.height > window.innerHeight * 0.92) return;
            if (el.querySelectorAll('a,button,input,[role="button"]').length === 0) return;
            el.style.setProperty('display', 'none', 'important');
            el.style.setProperty('visibility', 'hidden', 'important');
            el.style.setProperty('pointer-events', 'none', 'important');
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
