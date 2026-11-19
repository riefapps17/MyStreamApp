package com.example.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlockEngine {

    /**
     * Curated host blocklist covering major web ad networks, video VAST/pre-roll ad servers,
     * popunder/redirect networks, and invasive trackers. Subdomains are matched automatically.
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

        // Streaming site popunder, push-ad & redirect networks
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

        // Mobile & cross-site trackers / telemetry
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "clarity.ms",
        "taboola.com",
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
     * URL path/query regex patterns for ad scripts, VAST tags, and popunder loaders.
     */
    private val blockedUrlPatterns: List<Regex> = listOf(
        Regex("""/pagead/""", RegexOption.IGNORE_CASE),
        Regex("""/adserver/""", RegexOption.IGNORE_CASE),
        Regex("""/adservice/""", RegexOption.IGNORE_CASE),
        Regex("""/ads/(banner|js|iframe|pop|vast|vmap)""", RegexOption.IGNORE_CASE),
        Regex("""/(popunder|popads|popup_ad|interstitial_ad)""", RegexOption.IGNORE_CASE),
        Regex("""/(adsbygoogle|prebid|gpt|pubads_impl|ad_status|adframe|ads\.min)\.js""", RegexOption.IGNORE_CASE),
        Regex("""(vast|vmap)\.xml(\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""[?&](ad_tag|ad_unit|vast_url|popunder)=[^&]+""", RegexOption.IGNORE_CASE)
    )

    private val blockedSchemes = setOf("intent", "market", "itms-apps", "tel", "sms")

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

        // 3. Check ad script / VAST / popunder URL path patterns
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
     * Builds cosmetic CSS + anti-popup JavaScript injected into pages when AdBlock is active.
     */
    fun buildCosmeticScript(blockPopups: Boolean): String {
        val popupFlag = if (blockPopups) "true" else "false"
        return """
            (function() {
                try {
                    if (!document.getElementById('streamvault-adblock-css')) {
                        var style = document.createElement('style');
                        style.id = 'streamvault-adblock-css';
                        style.type = 'text/css';
                        style.textContent = [
                            '.adsbygoogle',
                            'ins.adsbygoogle',
                            '[id^="div-gpt-ad"]',
                            '[id^="google_ads_iframe"]',
                            '[class*="ad-banner"]',
                            '[class*="ad_banner"]',
                            '[class*="ads-container"]',
                            '[class*="ad-wrapper"]',
                            '[class*="sponsored-ad"]',
                            '[class*="popunder"]',
                            '[data-ad-slot]',
                            '[data-ad-client]',
                            'iframe[src*="doubleclick.net"]',
                            'iframe[src*="googlesyndication.com"]',
                            'iframe[src*="exoclick.com"]',
                            'iframe[src*="popads.net"]',
                            'iframe[src*="adsterra.com"]',
                            'a[href*="popads.net"]',
                            'a[href*="propellerads.com"]',
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

                    // Count & neutralize hidden ad elements on initial pass
                    if (!window.__streamVaultAdCleanupDone) {
                        window.__streamVaultAdCleanupDone = true;
                        var adNodes = document.querySelectorAll('.adsbygoogle, [id^="div-gpt-ad"], [class*="ad-banner"], #demo-ad-banner');
                        if (adNodes.length > 0) {
                            reportBlocked(adNodes.length);
                        }
                    }

                    // Neutralize rogue window.open popups and transparent click-hijack overlays
                    if ($popupFlag && !window.__streamVaultPopupGuard) {
                        window.__streamVaultPopupGuard = true;
                        var origOpen = window.open;
                        window.open = function(url) {
                            try {
                                if (!url || /pop|ad|click|track|promo|bet|casino/i.test(String(url))) {
                                    reportBlocked(1);
                                    return null;
                                }
                            } catch (e) {}
                            return origOpen ? origOpen.apply(this, arguments) : null;
                        };
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
