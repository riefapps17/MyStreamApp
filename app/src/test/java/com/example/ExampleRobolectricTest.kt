package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.AdBlockEngine
import com.example.browser.MediaFormat
import com.example.browser.MediaSniffer
import com.example.data.local.SearchEngine
import com.example.downloader.DashManifestParser
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
    fun `app name matches StreamVault`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("StreamVault", appName)
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

        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/app.js"))
        assertNull(MediaSniffer.classifyUrl("https://cdn.example.com/segment_001.ts"))
        assertNull(MediaSniffer.classifyUrl("blob:https://example.com/1234-5678"))
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
        val raw = "https://scontent.fbcdn.net/v/t42.1790-2/clip?efg=abc&bytestart=0&byteend=524287&oh=token99"
        val normalized = MediaSniffer.normalizeVideoUrl(raw)
        assertFalse(normalized.contains("bytestart="))
        assertFalse(normalized.contains("byteend="))
        assertTrue(normalized.contains("efg=abc"))
        assertTrue(normalized.contains("oh=token99"))
    }

    @Test
    fun `isProbeCandidate flags range and video destination requests`() {
        assertTrue(
            MediaSniffer.isProbeCandidate(
                url = "https://cdn.example.org/stream/8912345",
                acceptHeader = "*/*",
                rangeHeader = "bytes=0-65535",
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
    }
}
