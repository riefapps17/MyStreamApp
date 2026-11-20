package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.AdBlockEngine
import com.example.browser.MediaFormat
import com.example.browser.MediaSniffer
import com.example.data.local.SearchEngine
import com.example.downloader.DashManifestParser
import com.example.downloader.StreamDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches ArfStream`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ArfStream", appName)
    }

    @Test
    fun `classifyUrl detects mp4 hls webm and ignores static assets`() {
        assertEquals(
            MediaFormat.MP4,
            MediaSniffer.classifyUrl("https://cdn.example.com/videos/clip.mp4?token=123")
        )
        assertEquals(
            MediaFormat.HLS,
            MediaSniffer.classifyUrl("https://stream.example.com/live/master.m3u8?hdnea=abc")
        )
        assertEquals(
            MediaFormat.DASH,
            MediaSniffer.classifyUrl("https://stream.example.com/dash/manifest.mpd?sig=xyz")
        )
        assertEquals(
            MediaFormat.WEBM,
            MediaSniffer.classifyUrl("https://media.example.org/movie.webm")
        )
        assertEquals(
            MediaFormat.MP4,
            MediaSniffer.classifyUrl("https://video.cdn.net/videoplayback?itag=22&mime=video%2Fmp4")
        )
        // Disguised .txt HLS playlist once MIME is detected via probe/magic bytes
        assertEquals(
            MediaFormat.HLS,
            MediaSniffer.classifyUrl(
                "https://embed-cdn.example/hls2/01/master.txt?t=xyz",
                "application/vnd.apple.mpegurl"
            )
        )

        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/app.js"))
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/segment_001.ts"))
        assertNull(MediaSniffer.classifyUrl("blob:https://example.com/1234-5678"))
    }

    @Test
    fun `detectMimeFromMagicBytes identifies HLS DASH MP4 and WebM`() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            MediaSniffer.detectMimeFromMagicBytes("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray())
        )
        assertEquals(
            "application/dash+xml",
            MediaSniffer.detectMimeFromMagicBytes("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray())
        )
        val mp4Header = byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())
        assertEquals("video/mp4", MediaSniffer.detectMimeFromMagicBytes(mp4Header))

        val webmHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x01, 0x00)
        assertEquals("video/webm", MediaSniffer.detectMimeFromMagicBytes(webmHeader))

        assertNull(MediaSniffer.detectMimeFromMagicBytes("<!DOCTYPE html><html><body>403</body></html>".toByteArray()))
    }

    @Test
    fun `stripCloakedTsPrefix strips small and large fake prefixes while preserving fMP4`() {
        val fakePngPrefix = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val tsPayload = ByteArray(564) { 0x11 }
        tsPayload[0] = 0x47
        tsPayload[188] = 0x47
        tsPayload[376] = 0x47
        val cloaked = fakePngPrefix + tsPayload

        val cleaned = StreamDownloader.stripCloakedTsPrefix(cloaked)
        assertEquals(564, cleaned.size)
        assertEquals(0x47.toByte(), cleaned[0])
        assertEquals(0x47.toByte(), cleaned[188])

        // Larger fake image header (> 8 KB) should also be stripped cleanly
        val largeFakePrefix = ByteArray(10240) { 0x22 }
        val largeCloaked = largeFakePrefix + tsPayload
        val cleanedLarge = StreamDownloader.stripCloakedTsPrefix(largeCloaked)
        assertEquals(564, cleanedLarge.size)
        assertEquals(0x47.toByte(), cleanedLarge[0])

        // Valid fMP4 segment must never be stripped
        val fmp4 = ByteArray(600) { 0x00 }
        fmp4[4] = 'f'.code.toByte()
        fmp4[5] = 't'.code.toByte()
        fmp4[6] = 'y'.code.toByte()
        fmp4[7] = 'p'.code.toByte()
        fmp4[10] = 0x47
        fmp4[198] = 0x47
        fmp4[386] = 0x47
        val preservedFmp4 = StreamDownloader.stripCloakedTsPrefix(fmp4)
        assertEquals(600, preservedFmp4.size)
    }

    @Test
    fun `decodeDataUriKey decodes inline base64 AES-128 key`() {
        // 16 zero bytes in base64 -> "AAAAAAAAAAAAAAAAAAAAAA=="
        val decoded = StreamDownloader.decodeDataUriKey("data:application/octet-stream;base64,AAAAAAAAAAAAAAAAAAAAAA==")
        assertNotNull(decoded)
        assertEquals(16, decoded!!.size)
        assertNull(StreamDownloader.decodeDataUriKey("https://cdn.example.com/key.bin"))
    }

    @Test
    fun `classifyUrl detects extensionless social CDN video urls and filters audio`() {
        // TikTok extensionless CDN
        assertEquals(
            MediaFormat.MP4,
            MediaSniffer.classifyUrl("https://v16-webapp-prime.tiktok.com/video/tos/useast2a/tos-useast2a-ve-0068c001/abc123/?a=1988&mime_type=video_mp4")
        )
        // Instagram / Meta CDN video vs audio track
        assertEquals(
            MediaFormat.MP4,
            MediaSniffer.classifyUrl("https://scontent.cdninstagram.com/o1/v/t2/f2/m86/AQP123?efg=eyJ2ZW5jb2RlX3RhZyI6Inhwdl9wcm9ncmVzc2l2ZSJ9")
        )
        assertNull(
            MediaSniffer.classifyUrl("https://scontent.cdninstagram.com/o1/v/t2/f2/m86/AQP123?efg=dash_ln_heaac_vbr3_audio")
        )
        // X / Twitter CDN
        assertEquals(
            MediaFormat.MP4,
            MediaSniffer.classifyUrl("https://video.twimg.com/ext_tw_video/123456/pu/vid/720x1280/clip_no_ext?tag=12")
        )
    }

    @Test
    fun `normalizeVideoUrl strips byte range params and preserves tokens`() {
        val raw = "https://scontent.fbcdn.net/v/t42.1790-2/clip?efg=abc&bytestart=0&byteend=524287&range=0-65535&oh=token99"
        val normalized = MediaSniffer.normalizeVideoUrl(raw)
        assertFalse(normalized.contains("bytestart="))
        assertFalse(normalized.contains("byteend="))
        assertFalse(normalized.contains("range="))
        assertTrue(normalized.contains("efg=abc"))
        assertTrue(normalized.contains("oh=token99"))
    }

    @Test
    fun `classifyUrl and isTinyOrFragmentSize reject tiny fMP4 init and numbered segment chunks`() {
        // fMP4 / DASH initialization headers & numbered segment chunks must be ignored
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/hls/init.mp4"))
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/dash/init-stream0.mp4"))
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/video/seg-1-v1-a1.mp4"))
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/video/chunk_00012.mp4"))
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/video/frag-3.m4f"))
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/stream/track.mp4?sq=14"))

        // Direct videos under 300 KB are flagged as tiny fragments, while HLS/DASH playlists are never flagged
        assertTrue(MediaSniffer.isTinyOrFragmentSize(MediaFormat.MP4, 18 * 1024L))
        assertFalse(MediaSniffer.isTinyOrFragmentSize(MediaFormat.MP4, 2 * 1024 * 1024L))
        assertFalse(MediaSniffer.isTinyOrFragmentSize(MediaFormat.HLS, 4 * 1024L))
        assertFalse(MediaSniffer.isTinyOrFragmentSize(MediaFormat.DASH, 8 * 1024L))
    }

    @Test
    fun `isProbeCandidate flags range video destination and disguised playlists`() {
        assertTrue(
            MediaSniffer.isProbeCandidate(
                url = "https://cdn.example.org/stream/8912345",
                acceptHeader = "*/*",
                rangeHeader = "bytes=0-65535",
                secFetchDest = null
            )
        )
        // Mid-stream byte-range chunk fetch (not starting at byte 0) should not be probed as a separate video
        assertFalse(
            MediaSniffer.isProbeCandidate(
                url = "https://cdn.example.org/stream/8912345",
                acceptHeader = "*/*",
                rangeHeader = "bytes=65536-131071",
                secFetchDest = null
            )
        )
        assertTrue(
            MediaSniffer.isProbeCandidate(
                url = "https://cdn.example.org/play/item99",
                acceptHeader = "*/*",
                rangeHeader = null,
                secFetchDest = "video"
            )
        )
        assertTrue(
            MediaSniffer.isProbeCandidate(
                url = "https://embed.example.net/hls2/01/master.txt?t=abc",
                acceptHeader = "*/*",
                rangeHeader = null,
                secFetchDest = "empty"
            )
        )
        assertFalse(
            MediaSniffer.isProbeCandidate(
                url = "https://cdn.example.org/assets/logo.png",
                acceptHeader = "image/png",
                rangeHeader = "bytes=0-100",
                secFetchDest = "image"
            )
        )
    }

    @Test
    fun `dashManifestParser extracts best video and audio tracks and detects DRM`() {
        val mpdXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD mediaPresentationDuration="PT10S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v480" bandwidth="800000">
                    <SegmentTemplate media="seg_${'$'}RepresentationID${'$'}_${'$'}Number${'$'}.m4s" initialization="init_${'$'}RepresentationID${'$'}.mp4" startNumber="1" timescale="1000" duration="5000"/>
                  </Representation>
                  <Representation id="v1080" bandwidth="3200000">
                    <SegmentTemplate media="seg_${'$'}RepresentationID${'$'}_${'$'}Number${'$'}.m4s" initialization="init_${'$'}RepresentationID${'$'}.mp4" startNumber="1" timescale="1000" duration="5000"/>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a128" bandwidth="128000">
                    <BaseURL>audio_full.m4a</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val plan = DashManifestParser.parse("https://cdn.example.com/dash/manifest.mpd", mpdXml)
        assertFalse(plan.isDrmProtected)
        val videoTrack = plan.videoTrack
        val audioTrack = plan.audioTrack
        assertNotNull(videoTrack)
        assertNotNull(audioTrack)
        assertEquals(3200000L, videoTrack!!.bandwidth)
        assertEquals("https://cdn.example.com/dash/init_v1080.mp4", videoTrack.initUrl)
        assertEquals(2, videoTrack.segmentUrls.size)
        assertEquals("https://cdn.example.com/dash/seg_v1080_1.m4s", videoTrack.segmentUrls[0])
        assertEquals("https://cdn.example.com/dash/audio_full.m4a", audioTrack!!.singleBaseUrl)

        val drmXml = """
            <MPD>
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                  <Representation id="v1" bandwidth="1000000"><BaseURL>enc.mp4</BaseURL></Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val drmPlan = DashManifestParser.parse("https://cdn.example.com/drm/manifest.mpd", drmXml)
        assertTrue(drmPlan.isDrmProtected)
    }

    @Test
    fun `searchEngine defaults to Google and encodes search queries`() {
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId(null))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId("unknown"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromId("duckduckgo"))
        assertEquals(
            "https://www.google.com/search?q=video%20streaming",
            SearchEngine.GOOGLE.buildSearchUrl("video streaming")
        )
        assertEquals(
            "https://duckduckgo.com/?q=video%20streaming",
            SearchEngine.DUCKDUCKGO.buildSearchUrl("video streaming")
        )
        assertEquals(
            "https://www.bing.com/search?q=tutorial%20android",
            SearchEngine.BING.buildSearchUrl("tutorial android")
        )
    }

    @Test
    fun `formatBytes and formatDuration produce readable labels`() {
        assertEquals("512 KB", MediaSniffer.formatBytes(512 * 1024L))
        assertEquals("12.5 MB", MediaSniffer.formatBytes((12.5 * 1024 * 1024).toLong()))
        assertEquals("01:35", MediaSniffer.formatDuration(95))
        assertEquals("1:01:05", MediaSniffer.formatDuration(3665))
    }

    @Test
    fun `buildSafeFileName produces valid extension`() {
        val mp4Name = MediaSniffer.buildSafeFileName("Big Buck Bunny! (HD)", MediaFormat.MP4)
        assertTrue(mp4Name.endsWith(".mp4"))
        val hlsName = MediaSniffer.buildSafeFileName("Trailer Stream", MediaFormat.HLS)
        assertTrue(hlsName.endsWith(".ts"))
        val dashName = MediaSniffer.buildSafeFileName("Dash Movie", MediaFormat.DASH)
        assertTrue(dashName.endsWith(".mp4"))
    }

    @Test
    fun `adBlockEngine blocks ad hosts subdomains and patterns while preserving video streams`() {
        // Known ad networks & subdomains
        assertTrue(AdBlockEngine.shouldBlockRequest("https://pubads.g.doubleclick.net/gampad/ads?iu=123"))
        assertTrue(AdBlockEngine.shouldBlockRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdBlockEngine.shouldBlockRequest("https://cdn.popads.net/pop.js"))
        assertTrue(AdBlockEngine.shouldBlockRequest("https://ads.streaming-site.example/banner.js"))
        assertTrue(AdBlockEngine.shouldBlockRequest("https://example.com/player/vast.xml?zone=1"))

        // Legitimate video streams must NOT be blocked
        assertFalse(
            AdBlockEngine.shouldBlockRequest(
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
            )
        )
        assertFalse(
            AdBlockEngine.shouldBlockRequest(
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            )
        )
        assertFalse(
            AdBlockEngine.shouldBlockRequest("https://archive.org/details/movies")
        )
    }

    @Test
    fun `adBlockEngine blocks popunder and intent hijack navigations`() {
        assertTrue(
            AdBlockEngine.shouldBlockNavigation(
                targetUrl = "https://go.popads.net/click?id=99",
                currentPageUrl = "https://archive.org/details/movies",
                isAdBlockEnabled = true,
                isPopupBlockEnabled = true
            )
        )
        assertTrue(
            AdBlockEngine.shouldBlockNavigation(
                targetUrl = "intent://promo/#Intent;scheme=https;end",
                currentPageUrl = "https://archive.org/details/movies",
                isAdBlockEnabled = true,
                isPopupBlockEnabled = true
            )
        )
        assertFalse(
            AdBlockEngine.shouldBlockNavigation(
                targetUrl = "https://archive.org/details/BigBuckBunny_124",
                currentPageUrl = "https://archive.org/details/movies",
                isAdBlockEnabled = true,
                isPopupBlockEnabled = true
            )
        )

        // Anti-AdBlock scripts and cross-domain popup windows
        assertTrue(AdBlockEngine.shouldBlockRequest("https://cdn.blockadblock.com/blockadblock.js"))
        assertTrue(AdBlockEngine.shouldBlockRequest("https://streaming.example.com/assets/anti-adblock.min.js"))
        assertTrue(
            AdBlockEngine.shouldBlockPopupWindow(
                popupUrl = "https://random-promo-redirect.xyz/landing",
                currentPageUrl = "https://archive.org/details/movies",
                isAdBlockEnabled = true,
                isPopupBlockEnabled = true
            )
        )
        assertFalse(
            AdBlockEngine.shouldBlockPopupWindow(
                popupUrl = "https://ia800100.us.archive.org/details/BigBuckBunny",
                currentPageUrl = "https://archive.org/details/movies",
                isAdBlockEnabled = true,
                isPopupBlockEnabled = true
            )
        )

        val jsStubResp = AdBlockEngine.createBlockedResponse("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
        assertEquals("application/javascript", jsStubResp.mimeType)
        val stubBody = jsStubResp.data.bufferedReader().readText()
        assertTrue(stubBody.contains("window.canRunAds=true"))
    }

    @Test
    fun `videoThumbnailLoader safely ignores invalid data urls`() {
        assertNull(com.example.browser.VideoThumbnailLoader.decodeBase64DataUrl(""))
        assertNull(com.example.browser.VideoThumbnailLoader.decodeBase64DataUrl("https://example.com/poster.jpg"))
        assertNull(com.example.browser.VideoThumbnailLoader.decodeBase64DataUrl("data:image/jpeg;base64,"))
    }

    @Test
    fun `tabSessionSerializer round-trips multi-tab session and handles invalid json`() {
        val original = com.example.data.local.SavedTabSession(
            activeTabId = "tab-2",
            tabs = listOf(
                com.example.data.local.SavedTabRecord(
                    id = "tab-1",
                    url = "https://archive.org/details/movies",
                    title = "Internet Archive",
                    isHomeScreen = false,
                    isDesktopMode = false,
                    scrollY = 420
                ),
                com.example.data.local.SavedTabRecord(
                    id = "tab-2",
                    url = "https://vimeo.com/channels/staffpicks",
                    title = "Vimeo Staff Picks",
                    isHomeScreen = false,
                    isDesktopMode = true,
                    scrollY = 1280
                )
            )
        )
        val json = com.example.data.local.TabSessionSerializer.serialize(original)
        val restored = com.example.data.local.TabSessionSerializer.deserialize(json)
        assertNotNull(restored)
        assertEquals("tab-2", restored!!.activeTabId)
        assertEquals(2, restored.tabs.size)
        assertEquals("https://archive.org/details/movies", restored.tabs[0].url)
        assertEquals(420, restored.tabs[0].scrollY)
        assertTrue(restored.tabs[1].isDesktopMode)
        assertEquals(1280, restored.tabs[1].scrollY)

        assertNull(com.example.data.local.TabSessionSerializer.deserialize(null))
        assertNull(com.example.data.local.TabSessionSerializer.deserialize("{not valid json"))
    }

    @Test
    fun `video detection only accepts media from the active tab`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.MainViewModel(app)
        val tab1Id = vm.activeTabId.value
        vm.createNewTab()
        val tab2Id = vm.activeTabId.value
        assertFalse(tab1Id == tab2Id)

        // Attempt to report media from inactive tab 1 -> MUST be ignored
        vm.onMediaSniffed(
            tabId = tab1Id,
            url = "https://cdn.example.com/inactive_video.mp4",
            rawTitle = "Inactive Video",
            mimeHint = "video/mp4",
            resolution = "720p",
            durationSeconds = 60,
            referer = "https://example.com",
            originHeader = "",
            userAgent = "UA",
            cookie = "",
            origin = "Network"
        )
        val tab1After = vm.tabs.value.first { it.id == tab1Id }
        assertTrue(tab1After.detectedMedia.isEmpty())

        // Report media from active tab 2 -> MUST be accepted
        vm.onMediaSniffed(
            tabId = tab2Id,
            url = "https://cdn.example.com/active_stream.m3u8",
            rawTitle = "Active Stream",
            mimeHint = "application/vnd.apple.mpegurl",
            resolution = "1080p",
            durationSeconds = 120,
            referer = "https://example.com",
            originHeader = "",
            userAgent = "UA",
            cookie = "",
            origin = "Network"
        )
        val tab2After = vm.tabs.value.first { it.id == tab2Id }
        assertEquals(1, tab2After.detectedMedia.size)
        assertEquals("https://cdn.example.com/active_stream.m3u8", tab2After.detectedMedia[0].url)
    }
}
