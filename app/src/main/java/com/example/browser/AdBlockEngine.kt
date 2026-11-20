package com.example.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlockEngine {

    /**
     * Curated host blocklist covering major web ad networks, video VAST/pre-roll ad servers,
     * popunder/redirect networks, anti-adblock scripts, and invasive trackers.
     * Subdomains are matched automatically.
     */
    private val blockedHosts: Set<String> = hashSetOf(
        // Google & major programmatic display/video ad networks
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "imasdk.googleapis.com",
        "2mdn.net",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "smartadserver.com",
        "serving-sys.com",
        "advertising.com",
        "adform.net",
        "media.net",
        "moatads.com",
        "yieldmo.com",
        "bidswitch.net",
        "outbrain.com",
        "taboola.com",
        "mgid.com",
        "revcontent.com",
        "zedo.com",
        "adroll.com",
        "sharethrough.com",
        "triplelift.com",
        "indexww.com",
        "lijit.com",
        "sovrn.com",
        "teads.tv",
        "spotxchange.com",
        "spotx.tv",
        "springserve.com",
        "lkqd.net",
        "connatix.com",
        "jwpltx.com",

        // Streaming site popunder, push-ad, overlay & redirect networks
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propu.sh",
        "exoclick.com",
        "exosrv.com",
        "realsrv.com",
        "juicyads.com",
        "trafficjunky.net",
        "trafficfactory.biz",
        "adsterra.com",
        "highperformanceformat.com",
        "effectivegatecpm.com",
        "profitablegatecpm.com",
        "topcreativeformat.com",
        "hilltopads.net",
        "clickadu.com",
        "adcash.com",
        "acscdn.com",
        "onclickads.net",
        "onclkds.com",
        "admaven.com",
        "dolohen.com",
        "pushame.com",
        "notifpush.com",
        "galaksion.com",
        "monetag.com",
        "alwingulla.com",
        "a-ads.com",
        "bidvertiser.com",
        "infolinks.com",
        "chitika.net",
        "undertone.com",
        "gumgum.com",
        "kargo.com",
        "vidoomy.com",
        "bidgear.com",
        "pubfuture.com",
        "adskeeper.com",
        "adskeeper.co.uk",
        "evadav.com",
        "richads.com",
        "rollerads.com",
        "zeropark.com",
        "trafficstars.com",
        "plugrush.com",
        "adtng.com",
        "tsyndicate.com",
        "twinrdsyn.com",
        "acint.net",
        "adspyglass.com",

        // Anti-AdBlock script providers
        "blockadblock.com",
        "fuckadblock.com",
        "getadmiral.com",
        "admiral.com",
        "pagefair.com",
        "pagefair.net",

        // Mobile & cross-site trackers / telemetry
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "clarity.ms",
        "omtrdc.net",
        "demdex.net",
        "krxd.net",
        "bluekai.com",
        "exelator.com",
        "tapad.com",
        "rlcdn.com",
        "branch.io",
        "adjust.com",
        "appsflyer.com",
        "adcolony.com",
        "vungle.com",
        "applovin.com",
        "unityads.unity3d.com",
        "an.facebook.com",
        "pixel.facebook.com",
        "ads.twitter.com",
        "analytics.twitter.com",
        "ads.tiktok.com",
        "analytics.tiktok.com",
        "mc.yandex.ru",
        "an.yandex.ru"
    )

    /**
     * Host keywords that strongly indicate dedicated ad/tracker subdomains (e.g., ads.example.com).
     */
    private val adSubdomainPrefixes = listOf(
        "ad.",
        "ads.",
        "adserver.",
        "adservice.",
        "adsystem.",
        "adtrack.",
        "adclick.",
        "banner.",
        "banners.",
        "pop.",
        "pops.",
        "popunder.",
        "vast.",
        "vmap.",
        "track.",
        "tracker.",
        "metrics.",
        "pixel."
    )

    /**
     * URL path/query regex patterns for ad scripts, VAST tags, anti-adblock detectors, and popunder loaders.
     */
    private val blockedUrlPatterns: List<Regex> = listOf(
        Regex("""/pagead/""", RegexOption.IGNORE_CASE),
        Regex("""/adserver/""", RegexOption.IGNORE_CASE),
        Regex("""/adservice/""", RegexOption.IGNORE_CASE),
        Regex("""/ads/(banner|js|iframe|pop|vast|vmap)""", RegexOption.IGNORE_CASE),
        Regex("""/(popunder|popads|popup_ad|interstitial_ad)""", RegexOption.IGNORE_CASE),
        Regex("""/(adsbygoogle|prebid|gpt|pubads_impl|ad_status|adframe|ads\.min)\.js""", RegexOption.IGNORE_CASE),
        Regex("""/(blockadblock|fuckadblock|adblock[-_]?detector|anti[-_]?adblock|detect[-_]?adblock).*\.js""", RegexOption.IGNORE_CASE),
        Regex("""(vast|vmap)\.xml(\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""[?&](ad_tag|ad_unit|vast_url|popunder)=[^&]+""", RegexOption.IGNORE_CASE)
    )

    private val blockedSchemes = setOf("intent", "market", "itms-apps", "tel", "sms")

    private const val FAKE_AD_JS_STUB =
        "window.canRunAds=true;window.isAdBlockActive=false;window.adBlockEnabled=false;" +
            "window.adsbygoogle=window.adsbygoogle||[];window.adsbygoogle.loaded=true;" +
            "window.google_ad_modifications=window.google_ad_modifications||{};"

    /**
     * Checks whether a host or any of its parent domains is in [blockedHosts]
     * or starts with an ad-dedicated subdomain prefix.
     */
    fun isAdHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US).trim().trimEnd('.')
        if (normalized.isEmpty()) return false

        // Exact or parent domain check: sub.doubleclick.net -> doubleclick.net
        var candidate = normalized
        while (candidate.isNotEmpty()) {
            if (candidate in blockedHosts) return true
            val dotIndex = candidate.indexOf('.')
            if (dotIndex < 0) break
            candidate = candidate.substring(dotIndex + 1)
        }

        // Dedicated ad subdomain prefix check (skip localhost / internal streamvault host)
        if (normalized != "streamvault.local" && normalized.count { it == '.' } >= 2) {
            if (adSubdomainPrefixes.any { normalized.startsWith(it) }) {
                return true
            }
        }

        return false
    }

    /**
     * Determines whether an HTTP/HTTPS sub-resource request should be blocked.
     * Protects legitimate video streams unless they come directly from a known ad server host.
     */
    fun shouldBlockRequest(url: String, pageHost: String = ""): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (host.isEmpty() || host == "streamvault.local") return false

        // 1. Always block requests to known ad/tracker/VAST hosts
        if (isAdHost(host)) {
            return true
        }

        // 2. Never falsely block genuine non-ad media streams (MP4/M3U8/WebM)
        if (MediaSniffer.classifyUrl(url) != null) {
            return false
        }

        // 3. Check ad script / VAST / popunder / anti-adblock URL path patterns
        val pathAndQuery = buildString {
            append(uri.encodedPath.orEmpty())
            val q = uri.encodedQuery
            if (!q.isNullOrEmpty()) {
                append('?')
                append(q)
            }
        }
        if (blockedUrlPatterns.any { it.containsMatchIn(pathAndQuery) }) {
            return true
        }

        // 4. Block third-party ad-tracker pixel endpoints on different domains
        val normalizedPageHost = pageHost.lowercase(Locale.US).trim()
        if (normalizedPageHost.isNotEmpty() &&
            !host.endsWith(normalizedPageHost) &&
            !normalizedPageHost.endsWith(host)
        ) {
            val lowerPath = pathAndQuery.lowercase(Locale.US)
            if (lowerPath.contains("/collect?") && lowerPath.contains("tid=ua-")) {
                return true
            }
        }

        return false
    }

    /**
     * Determines whether a top-level or popup navigation should be intercepted and blocked.
     */
    fun shouldBlockNavigation(
        targetUrl: String,
        currentPageUrl: String,
        isAdBlockEnabled: Boolean,
        isPopupBlockEnabled: Boolean
    ): Boolean {
        if (targetUrl.isBlank()) return false
        val uri = runCatching { Uri.parse(targetUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()

        // Block malicious app-store / intent hijack redirects from streaming sites
        if (isPopupBlockEnabled && scheme in blockedSchemes) {
            return true
        }

        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (host.isEmpty()) return false

        if (isAdBlockEnabled && isAdHost(host)) {
            return true
        }

        if (isPopupBlockEnabled) {
            val currentHost = runCatching { Uri.parse(currentPageUrl).host?.lowercase(Locale.US) }
                .getOrNull()
                .orEmpty()
            val pathAndQuery = (uri.encodedPath.orEmpty() + "?" + uri.encodedQuery.orEmpty())
            val isCrossDomain = currentHost.isNotEmpty() &&
                !host.endsWith(currentHost) &&
                !currentHost.endsWith(host)
            if (isCrossDomain && blockedUrlPatterns.any { it.containsMatchIn(pathAndQuery) }) {
                return true
            }
        }

        return false
    }

    /**
     * Checks whether a newly spawned popup window URL should be blocked when Popup Blocker is active.
     * In addition to ad hosts and ad URL patterns, blocks unsolicited cross-domain popup windows.
     */
    fun shouldBlockPopupWindow(
        popupUrl: String,
        currentPageUrl: String,
        isAdBlockEnabled: Boolean,
        isPopupBlockEnabled: Boolean
    ): Boolean {
        if (!isPopupBlockEnabled) return false
        if (popupUrl.isBlank() || popupUrl.equals("about:blank", ignoreCase = true)) {
            return true
        }
        if (shouldBlockNavigation(popupUrl, currentPageUrl, isAdBlockEnabled, isPopupBlockEnabled)) {
            return true
        }
        val currentHost = runCatching { Uri.parse(currentPageUrl).host?.lowercase(Locale.US) }
            .getOrNull()
            .orEmpty()
        val targetHost = runCatching { Uri.parse(popupUrl).host?.lowercase(Locale.US) }
            .getOrNull()
            .orEmpty()
        if (currentHost.isNotEmpty() && targetHost.isNotEmpty()) {
            val sameSite = targetHost == currentHost ||
                targetHost.endsWith(".$currentHost") ||
                currentHost.endsWith(".$targetHost")
            if (!sameSite) {
                return true
            }
        }
        return false
    }

    /**
     * Creates a response for blocked sub-resources.
     * For blocked .js scripts, returns a harmless JS stub that sets `window.canRunAds = true`
     * so anti-adblock detectors do not trigger `script.onerror` or missing-variable popups.
     */
    fun createBlockedResponse(url: String = ""): WebResourceResponse {
        val lower = url.lowercase(Locale.US).substringBefore('?').substringBefore('#')
        if (lower.endsWith(".js")) {
            return WebResourceResponse(
                "application/javascript",
                "utf-8",
                ByteArrayInputStream(FAKE_AD_JS_STUB.toByteArray(Charsets.UTF_8))
            )
        }
        return createEmptyResponse()
    }

    /**
     * Creates a lightweight empty 200 response to short-circuit blocked ad requests cleanly.
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Builds cosmetic CSS + 4-layer anti-popup & anti-adblock JavaScript injected into WebView pages.
     * Operates strictly inside WebView DOM and never touches native Android Compose ModalBottomSheets.
     */
    fun buildCosmeticScript(blockPopups: Boolean): String {
        val popupFlag = if (blockPopups) "true" else "false"
        return """
            (function() {
                try {
                    // --- LAYER 1: Anti-AdBlock Defuser (Bait Variables & Stubs) ---
                    window.canRunAds = true;
                    window.isAdBlockActive = false;
                    window.adBlockEnabled = false;
                    window.adblock = false;
                    window.adblocker = false;
                    window.google_ad_modifications = window.google_ad_modifications || {};
                    if (!window.adsbygoogle || !window.adsbygoogle.loaded) {
                        window.adsbygoogle = window.adsbygoogle || [];
                        window.adsbygoogle.loaded = true;
                        window.adsbygoogle.push = function() { return 0; };
                    }
                    if (!document.getElementById('arf-ad-bait')) {
                        var bait = document.createElement('div');
                        bait.id = 'arf-ad-bait';
                        bait.className = 'adsbox ad-banner ad-placement pub_300x250 pub_300x250m text-ad textAd';
                        bait.style.cssText = 'position:absolute !important;left:-9999px !important;top:-9999px !important;width:300px !important;height:250px !important;display:block !important;visibility:visible !important;pointer-events:none !important;';
                        bait.innerHTML = '&nbsp;';
                        (document.body || document.documentElement).appendChild(bait);
                    }

                    // --- Cosmetic CSS Filter ---
                    if (!document.getElementById('streamvault-adblock-css')) {
                        var style = document.createElement('style');
                        style.id = 'streamvault-adblock-css';
                        style.type = 'text/css';
                        style.textContent = [
                            '.adsbygoogle',
                            'ins.adsbygoogle',
                            '[id^="div-gpt-ad"]',
                            '[id^="google_ads_iframe"]',
                            '[class*="ad-banner"]:not(#arf-ad-bait)',
                            '[class*="ad_banner"]',
                            '[class*="ads-container"]',
                            '[class*="ad-wrapper"]',
                            '[class*="sponsored-ad"]',
                            '[class*="popunder"]',
                            '[id*="adblock-modal" i]',
                            '[class*="adblock-modal" i]',
                            '[id*="anti-adblock" i]',
                            '[class*="anti-adblock" i]',
                            '[id*="adb-detected" i]',
                            '[class*="adb-detected" i]',
                            '[data-ad-slot]',
                            '[data-ad-client]',
                            'iframe[src*="doubleclick.net"]',
                            'iframe[src*="googlesyndication.com"]',
                            'iframe[src*="exoclick.com"]',
                            'iframe[src*="popads.net"]',
                            'iframe[src*="adsterra.com"]',
                            'iframe[src*="propellerads.com"]',
                            'a[href*="popads.net"]',
                            'a[href*="propellerads.com"]',
                            'a[href*="adsterra.com"]',
                            '#demo-ad-banner'
                        ].join(',') + ' { display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; pointer-events: none !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    }

                    function reportBlocked(count) {
                        try {
                            if (count > 0 && window.StreamVaultBridge && window.StreamVaultBridge.onCosmeticBlocked) {
                                window.StreamVaultBridge.onCosmeticBlocked(count);
                            }
                        } catch (e) {}
                    }

                    function reportPopupBlocked(url) {
                        try {
                            if (window.StreamVaultBridge && window.StreamVaultBridge.onPopupBlockedInJs) {
                                window.StreamVaultBridge.onPopupBlockedInJs(String(url || window.location.href));
                            } else {
                                reportBlocked(1);
                            }
                        } catch (e) {}
                    }

                    function isSameOrSubdomain(targetHost, currentHost) {
                        if (!targetHost || !currentHost) return true;
                        var t = targetHost.toLowerCase();
                        var c = currentHost.toLowerCase();
                        return t === c || t.endsWith('.' + c) || c.endsWith('.' + t);
                    }

                    function containsMediaPlayer(el) {
                        if (!el || !el.querySelector) return false;
                        if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') return true;
                        if (el.querySelector('video, audio, .jwplayer, .video-js, .plyr, [class*="jw-"], [class*="vjs-"]')) return true;
                        return false;
                    }

                    // --- LAYER 2: Anti-AdBlock Modal & Transparent Click-Hijack Overlay Sweeper ---
                    function sweepInPagePopupsAndOverlays() {
                        try {
                            var removedCount = 0;
                            var vw = Math.max(window.innerWidth || 0, 320);
                            var vh = Math.max(window.innerHeight || 0, 480);

                            // 2A. Dismiss anti-adblock warning modals & transparent click-hijack overlays
                            var candidates = document.querySelectorAll('div, section, aside, dialog, iframe, a');
                            for (var i = 0; i < candidates.length && i < 240; i++) {
                                var el = candidates[i];
                                if (!el || el.id === 'arf-ad-bait' || el.getAttribute('data-arf-cleaned') === '1') continue;
                                if (containsMediaPlayer(el)) continue;

                                var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
                                if (!cs || cs.display === 'none' || cs.visibility === 'hidden') continue;

                                var pos = cs.position;
                                var zIndex = parseInt(cs.zIndex, 10);
                                if (isNaN(zIndex)) zIndex = 0;

                                // Detect Anti-AdBlock modal dialogs ("Disable Adblock" / "Matikan Adblock")
                                if ((pos === 'fixed' || pos === 'absolute' || el.tagName === 'DIALOG') && zIndex >= 50) {
                                    var idCls = ((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '')).toLowerCase();
                                    var txt = (el.innerText || el.textContent || '').toLowerCase();
                                    var looksLikeAntiAdblockModal =
                                        /anti[-_]?adblock|adblock[-_]?modal|adb[-_]?modal|blockadblock|fuckadblock/i.test(idCls) ||
                                        (txt.length > 15 && txt.length < 1200 &&
                                            /(adblock|ad blocker|pemblokir iklan)/i.test(txt) &&
                                            /(disable|turn off|matikan|nonaktifkan|detected|terdeteksi|whitelist)/i.test(txt));
                                    if (looksLikeAntiAdblockModal) {
                                        el.setAttribute('data-arf-cleaned', '1');
                                        el.style.setProperty('display', 'none', 'important');
                                        el.style.setProperty('pointer-events', 'none', 'important');
                                        if (document.body) document.body.style.setProperty('overflow', 'auto', 'important');
                                        if (document.documentElement) document.documentElement.style.setProperty('overflow', 'auto', 'important');
                                        removedCount++;
                                        continue;
                                    }
                                }

                                // Detect transparent full-screen / player-covering click-hijack overlays
                                if ($popupFlag && (pos === 'fixed' || pos === 'absolute') && zIndex >= 900) {
                                    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                                    if (!rect) continue;
                                    var coversScreen = rect.width >= vw * 0.65 && rect.height >= vh * 0.45;
                                    if (!coversScreen) continue;

                                    var opacity = parseFloat(cs.opacity || '1');
                                    var bg = (cs.backgroundColor || '').replace(/\s+/g, '');
                                    var isTransparent = opacity <= 0.08 || bg === 'transparent' || bg === 'rgba(0,0,0,0)';
                                    var hasNoVisibleText = (el.innerText || '').trim().length === 0;
                                    var hasNoControls = !el.querySelector('input, button, select, textarea, form, nav');

                                    if (isTransparent && hasNoVisibleText && hasNoControls) {
                                        el.setAttribute('data-arf-cleaned', '1');
                                        el.style.setProperty('display', 'none', 'important');
                                        el.style.setProperty('pointer-events', 'none', 'important');
                                        removedCount++;
                                    }
                                }
                            }

                            if (removedCount > 0) {
                                reportBlocked(removedCount);
                            }
                        } catch (e) {}
                    }

                    // Initial cleanup pass
                    if (!window.__streamVaultAdCleanupDone) {
                        window.__streamVaultAdCleanupDone = true;
                        var adNodes = document.querySelectorAll('.adsbygoogle, [id^="div-gpt-ad"], [class*="ad-banner"]:not(#arf-ad-bait), #demo-ad-banner');
                        if (adNodes.length > 0) {
                            reportBlocked(adNodes.length);
                        }
                    }
                    sweepInPagePopupsAndOverlays();

                    // --- LAYER 3: Strict window.open & Dynamic <a target="_blank"> Click-Hijack Guard ---
                    if ($popupFlag && !window.__streamVaultPopupGuard) {
                        window.__streamVaultPopupGuard = true;

                        var origOpen = window.open;
                        window.open = function(url) {
                            try {
                                var raw = String(url || '').trim();
                                if (!raw || raw === 'about:blank' || /pop|ad|click|track|promo|bet|casino|offer|redirect|exoclick|adsterra|propeller/i.test(raw)) {
                                    reportPopupBlocked(raw);
                                    return null;
                                }
                                var parsed = new URL(raw, window.location.href);
                                if (!isSameOrSubdomain(parsed.hostname, window.location.hostname)) {
                                    reportPopupBlocked(parsed.href);
                                    return null;
                                }
                            } catch (e) {
                                reportPopupBlocked(url);
                                return null;
                            }
                            return origOpen ? origOpen.apply(this, arguments) : null;
                        };

                        // Intercept invisible / hijacked <a target="_blank"> clicks injected over video players
                        document.addEventListener('click', function(e) {
                            try {
                                var target = e.target;
                                while (target && target !== document && target.tagName !== 'A') {
                                    target = target.parentElement;
                                }
                                if (!target || target.tagName !== 'A') return;
                                var href = target.getAttribute('href') || target.href || '';
                                var opensNewWindow = (target.getAttribute('target') || '').toLowerCase() === '_blank';
                                if (!href) return;

                                var isAdOrPopPattern = /popads|propeller|adsterra|exoclick|clickadu|hilltopads|adcash|onclickads|monetag|popunder|redirect|bet|casino/i.test(href);
                                var cs = window.getComputedStyle ? window.getComputedStyle(target) : null;
                                var isInvisibleOverlayLink = cs && (cs.position === 'fixed' || cs.position === 'absolute') &&
                                    (parseFloat(cs.opacity || '1') <= 0.1 || ((target.innerText || '').trim().length === 0 && !target.querySelector('img, svg')));

                                if (isAdOrPopPattern || (opensNewWindow && isInvisibleOverlayLink)) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
                                    target.style.setProperty('display', 'none', 'important');
                                    target.style.setProperty('pointer-events', 'none', 'important');
                                    reportPopupBlocked(href);
                                }
                            } catch (err) {}
                        }, true);

                        // Observe dynamic DOM mutations so delayed anti-adblock modals & click overlays are neutralized
                        if (window.MutationObserver && !window.__arfPopupObserver) {
                            var sweepTimer = null;
                            window.__arfPopupObserver = new MutationObserver(function() {
                                if (sweepTimer) return;
                                sweepTimer = setTimeout(function() {
                                    sweepTimer = null;
                                    sweepInPagePopupsAndOverlays();
                                }, 350);
                            });
                            window.__arfPopupObserver.observe(document.documentElement || document.body, {
                                childList: true,
                                subtree: true
                            });
                        }
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
