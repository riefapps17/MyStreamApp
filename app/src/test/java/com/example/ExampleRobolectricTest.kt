package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.AdBlockEngine
import com.example.browser.MediaFormat
import com.example.browser.MediaSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
