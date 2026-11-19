package com.example.downloader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URL
import java.util.Locale

data class DashTrackPlan(
    val isVideo: Boolean,
    val mimeType: String,
    val bandwidth: Long,
    val initUrl: String?,
    val segmentUrls: List<String>,
    val singleBaseUrl: String?
)

data class DashManifestPlan(
    val isDrmProtected: Boolean,
    val videoTrack: DashTrackPlan?,
    val audioTrack: DashTrackPlan?
)

object DashManifestParser {

    private data class SegmentTemplateInfo(
        val media: String = "",
        val initialization: String = "",
        val startNumber: Long = 1L,
        val timescale: Long = 1L,
        val duration: Long = 0L,
        val timelineEntries: List<TimelineEntry> = emptyList()
    )

    private data class TimelineEntry(
        val t: Long?,
        val d: Long,
        val r: Int
    )

    fun parse(manifestUrl: String, xmlContent: String): DashManifestPlan {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlContent))

        var isDrm = false
        var mpdBaseUrl: String? = null
        var periodDurationSec = 0.0
        var mpdDurationSec = 0.0

        var inAdaptationSet = false
        var inRepresentation = false
        var inSegmentTemplate = false
        var inSegmentList = false

        var adaptMime = ""
        var adaptContentType = ""
        var adaptBaseUrl: String? = null
        var adaptTemplate: SegmentTemplateInfo? = null

        var repId = ""
        var repMime = ""
        var repBandwidth = 0L
        var repBaseUrl: String? = null
        var repTemplate: SegmentTemplateInfo? = null
        var repListInit: String? = null
        val repListSegments = mutableListOf<String>()

        var currentTemplate: SegmentTemplateInfo? = null
        val currentTimeline = mutableListOf<TimelineEntry>()

        val videoCandidates = mutableListOf<DashTrackPlan>()
        val audioCandidates = mutableListOf<DashTrackPlan>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty().lowercase(Locale.US)
                    when (name) {
                        "mpd" -> {
                            val dur = parser.getAttributeValue(null, "mediaPresentationDuration")
                            if (!dur.isNullOrBlank()) mpdDurationSec = parseIso8601Duration(dur)
                        }
                        "period" -> {
                            val dur = parser.getAttributeValue(null, "duration")
                            if (!dur.isNullOrBlank()) periodDurationSec = parseIso8601Duration(dur)
                        }
                        "contentprotection" -> {
                            val scheme = parser.getAttributeValue(null, "schemeIdUri").orEmpty().lowercase(Locale.US)
                            // Widevine, PlayReady, FairPlay, or generic CENC DRM
                            if (scheme.contains("edef8ba9") ||
                                scheme.contains("9a04f079") ||
                                scheme.contains("cenc") ||
                                scheme.contains("drm")
                            ) {
                                isDrm = true
                            }
                        }
                        "adaptationset" -> {
                            inAdaptationSet = true
                            adaptMime = parser.getAttributeValue(null, "mimeType").orEmpty()
                            adaptContentType = parser.getAttributeValue(null, "contentType").orEmpty()
                            adaptBaseUrl = null
                            adaptTemplate = null
                        }
                        "representation" -> {
                            inRepresentation = true
                            repId = parser.getAttributeValue(null, "id").orEmpty()
                            repMime = parser.getAttributeValue(null, "mimeType").orEmpty()
                            repBandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                            repBaseUrl = null
                            repTemplate = null
                            repListInit = null
                            repListSegments.clear()
                        }
                        "baseurl" -> {
                            val text = runCatching { parser.nextText().trim() }.getOrDefault("")
                            if (text.isNotEmpty()) {
                                when {
                                    inRepresentation -> repBaseUrl = text
                                    inAdaptationSet -> adaptBaseUrl = text
                                    else -> mpdBaseUrl = text
                                }
                            }
                        }
                        "segmenttemplate" -> {
                            inSegmentTemplate = true
                            currentTimeline.clear()
                            currentTemplate = SegmentTemplateInfo(
                                media = parser.getAttributeValue(null, "media").orEmpty(),
                                initialization = parser.getAttributeValue(null, "initialization").orEmpty(),
                                startNumber = parser.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L,
                                timescale = parser.getAttributeValue(null, "timescale")?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L,
                                duration = parser.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L
                            )
                        }
                        "s" -> {
                            if (inSegmentTemplate) {
                                val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                                val d = parser.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                                val r = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                                if (d > 0L) {
                                    currentTimeline.add(TimelineEntry(t = t, d = d, r = r))
                                }
                            }
                        }
                        "segmentlist" -> {
                            inSegmentList = true
                        }
                        "initialization" -> {
                            if (inSegmentList && inRepresentation) {
                                repListInit = parser.getAttributeValue(null, "sourceURL")
                            }
                        }
                        "segmenturl" -> {
                            if (inSegmentList && inRepresentation) {
                                val media = parser.getAttributeValue(null, "media")
                                if (!media.isNullOrBlank()) {
                                    repListSegments.add(media)
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name.orEmpty().lowercase(Locale.US)
                    when (name) {
                        "segmenttemplate" -> {
                            val completed = currentTemplate?.copy(timelineEntries = currentTimeline.toList())
                            if (inRepresentation) {
                                repTemplate = completed
                            } else if (inAdaptationSet) {
                                adaptTemplate = completed
                            }
                            inSegmentTemplate = false
                            currentTemplate = null
                        }
                        "segmentlist" -> {
                            inSegmentList = false
                        }
                        "representation" -> {
                            val effectiveMime = repMime.ifBlank { adaptMime }.lowercase(Locale.US)
                            val isVideo = effectiveMime.startsWith("video/") ||
                                adaptContentType.equals("video", ignoreCase = true)
                            val isAudio = effectiveMime.startsWith("audio/") ||
                                adaptContentType.equals("audio", ignoreCase = true)

                            if (isVideo || isAudio) {
                                val base = resolveChain(manifestUrl, mpdBaseUrl, adaptBaseUrl, repBaseUrl)
                                val template = repTemplate ?: adaptTemplate
                                val durationSec = if (periodDurationSec > 0.0) periodDurationSec else mpdDurationSec

                                val plan = when {
                                    template != null && template.media.isNotBlank() -> {
                                        buildTemplatePlan(
                                            isVideo = isVideo,
                                            mime = effectiveMime,
                                            bandwidth = repBandwidth,
                                            repId = repId,
                                            baseUrl = base,
                                            template = template,
                                            durationSec = durationSec
                                        )
                                    }
                                    repListSegments.isNotEmpty() -> {
                                        DashTrackPlan(
                                            isVideo = isVideo,
                                            mimeType = effectiveMime,
                                            bandwidth = repBandwidth,
                                            initUrl = repListInit?.let { resolveRelative(base, it) },
                                            segmentUrls = repListSegments.map { resolveRelative(base, it) },
                                            singleBaseUrl = null
                                        )
                                    }
                                    !repBaseUrl.isNullOrBlank() || !adaptBaseUrl.isNullOrBlank() -> {
                                        DashTrackPlan(
                                            isVideo = isVideo,
                                            mimeType = effectiveMime,
                                            bandwidth = repBandwidth,
                                            initUrl = null,
                                            segmentUrls = emptyList(),
                                            singleBaseUrl = base
                                        )
                                    }
                                    else -> null
                                }

                                if (plan != null) {
                                    if (isVideo) videoCandidates.add(plan) else audioCandidates.add(plan)
                                }
                            }
                            inRepresentation = false
                        }
                        "adaptationset" -> {
                            inAdaptationSet = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val bestVideo = videoCandidates
            .sortedWith(
                compareByDescending<DashTrackPlan> { it.mimeType.contains("mp4") }
                    .thenByDescending { it.bandwidth }
            )
            .firstOrNull()
        val bestAudio = audioCandidates
            .sortedWith(
                compareByDescending<DashTrackPlan> { it.mimeType.contains("mp4") || it.mimeType.contains("aac") }
                    .thenByDescending { it.bandwidth }
            )
            .firstOrNull()

        return DashManifestPlan(
            isDrmProtected = isDrm,
            videoTrack = bestVideo,
            audioTrack = bestAudio
        )
    }

    private fun buildTemplatePlan(
        isVideo: Boolean,
        mime: String,
        bandwidth: Long,
        repId: String,
        baseUrl: String,
        template: SegmentTemplateInfo,
        durationSec: Double
    ): DashTrackPlan {
        fun replaceVars(pattern: String, number: Long? = null, time: Long? = null): String {
            var out = pattern
                .replace("\$RepresentationID\$", repId)
                .replace("\$Bandwidth\$", bandwidth.toString())
            if (number != null) {
                out = Regex("\\\$Number(%0(\\d+)d)?\\\$").replace(out) { m ->
                    val width = m.groupValues.getOrNull(2)?.toIntOrNull()
                    if (width != null) number.toString().padStart(width, '0') else number.toString()
                }
            }
            if (time != null) {
                out = Regex("\\\$Time(%0(\\d+)d)?\\\$").replace(out) { m ->
                    val width = m.groupValues.getOrNull(2)?.toIntOrNull()
                    if (width != null) time.toString().padStart(width, '0') else time.toString()
                }
            }
            return out
        }

        val initUrl = template.initialization
            .takeIf { it.isNotBlank() }
            ?.let { resolveRelative(baseUrl, replaceVars(it)) }

        val segments = mutableListOf<String>()
        if (template.timelineEntries.isNotEmpty()) {
            var currentTime = 0L
            var currentNum = template.startNumber
            for (entry in template.timelineEntries) {
                if (entry.t != null) currentTime = entry.t
                val repeatCount = entry.r.coerceIn(0, 2000)
                for (i in 0..repeatCount) {
                    segments.add(
                        resolveRelative(baseUrl, replaceVars(template.media, number = currentNum, time = currentTime))
                    )
                    currentTime += entry.d
                    currentNum++
                }
            }
        } else if (template.duration > 0L && durationSec > 0.0) {
            val segDurSec = template.duration.toDouble() / template.timescale.toDouble()
            val count = kotlin.math.ceil(durationSec / segDurSec).toInt().coerceIn(1, 2000)
            for (i in 0 until count) {
                val num = template.startNumber + i
                segments.add(resolveRelative(baseUrl, replaceVars(template.media, number = num)))
            }
        }

        return DashTrackPlan(
            isVideo = isVideo,
            mimeType = mime,
            bandwidth = bandwidth,
            initUrl = initUrl,
            segmentUrls = segments,
            singleBaseUrl = null
        )
    }

    private fun resolveChain(root: String, vararg parts: String?): String {
        var acc = root
        for (p in parts) {
            if (!p.isNullOrBlank()) {
                acc = resolveRelative(acc, p)
            }
        }
        return acc
    }

    private fun resolveRelative(base: String, rel: String): String {
        return runCatching { URL(URL(base), rel).toString() }.getOrElse { rel }
    }

    private fun parseIso8601Duration(raw: String): Double {
        val match = Regex("""PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""")
            .find(raw.uppercase(Locale.US)) ?: return 0.0
        val h = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val m = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = match.groupValues[3].toDoubleOrNull() ?: 0.0
        return h * 3600.0 + m * 60.0 + s
    }
}
