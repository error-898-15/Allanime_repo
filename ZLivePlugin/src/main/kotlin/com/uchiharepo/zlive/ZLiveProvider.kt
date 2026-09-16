package com.uchiharepo.zlive

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ZLiveProvider : MainAPI() {
    override var mainUrl = "https://zlive.st"
    override var name = "ZLive"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Live
    )

    companion object {
        private const val API_CHANNELS = "https://cast.zlive.st/channels.json"
        private const val API_STREAMS = "https://cast.zlive.st/streams"
        private const val API_RESOLVE = "https://iptv.zlive.st/resolve"
        private const val SECRET = "1lNAwCy_A2PnbE5sIpWwjyDI9WeN--37BqUuLOD2aI4"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val DEFAULT_POSTER =
            "https://raw.githubusercontent.com/error-898-15/Uchiharepo/main/icon.png"
    }

    data class ZLiveSource(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    data class ZLiveChannel(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("tagline") val tagline: String? = null,
        @JsonProperty("region") val region: String? = null,
        @JsonProperty("flag") val flag: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("accent") val accent: String? = null,
        @JsonProperty("live") val live: Boolean? = true,
        @JsonProperty("sport") val sport: String? = null,
        @JsonProperty("order") val order: Int? = 0,
        @JsonProperty("sources") val sources: List<ZLiveSource>? = null
    )

    data class ZLiveStreamEvent(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("startTime") val startTime: String? = null,
        @JsonProperty("live") val live: Boolean? = true,
        @JsonProperty("order") val order: Int? = 0,
        @JsonProperty("source") val source: ZLiveSource? = null
    )

    data class ZLivePassData(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("sources") val sources: List<ZLiveSource> = emptyList(),
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("tagline") val tagline: String? = null
    )

    data class ZLiveResolveResponse(
        @JsonProperty("location") val location: String? = null
    )

    private fun getUtcDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun base64Encode(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun encryptPayload(payloadJson: String, dateStr: String): Map<String, String> {
        val keyMaterial = "$SECRET:$dateStr".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val aesKey = digest.digest(keyMaterial)
        val secretKey = SecretKeySpec(aesKey, "AES")

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

        val cipherTextWithTag = cipher.doFinal(payloadJson.toByteArray(Charsets.UTF_8))
        val tagLength = 16
        val cipherTextLength = cipherTextWithTag.size - tagLength
        val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextLength)
        val tag = cipherTextWithTag.copyOfRange(cipherTextLength, cipherTextWithTag.size)

        return mapOf(
            "q" to base64Encode(cipherText),
            "s" to base64Encode(iv),
            "t" to base64Encode(tag),
            "d" to dateStr
        )
    }

    private suspend fun fetchChannels(): List<ZLiveChannel> {
        return try {
            val res = app.get(
                API_CHANNELS,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            ).text
            parseJson<List<ZLiveChannel>>(res)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchStreams(): List<ZLiveStreamEvent> {
        return try {
            val dateStr = getUtcDateString()
            val ts = System.currentTimeMillis() / 1000
            val payload = """{"ts":$ts}"""
            val body = encryptPayload(payload, dateStr)
            val res = app.post(
                API_STREAMS,
                json = body,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            ).text
            parseJson<List<ZLiveStreamEvent>>(res)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun ZLiveChannel.toSearchResponse(): SearchResponse? {
        val channelId = id ?: return null
        val title = name ?: "Live Channel"
        val poster = if (!flag.isNullOrBlank()) {
            "https://flagcdn.com/w160/${flag.lowercase()}.png"
        } else {
            DEFAULT_POSTER
        }
        return newMovieSearchResponse(
            name = title,
            url = "$mainUrl/channel/$channelId",
            type = TvType.Live
        ) {
            this.posterUrl = poster
        }
    }

    private fun ZLiveStreamEvent.toSearchResponse(): SearchResponse? {
        val streamId = id ?: return null
        val title = name ?: "Live Event"
        val poster = thumbnail?.takeIf { it.isNotBlank() } ?: DEFAULT_POSTER
        return newMovieSearchResponse(
            name = title,
            url = "$mainUrl/stream/$streamId",
            type = TvType.Live
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeSections = ArrayList<HomePageList>()

        // 1. Live Sports & Events
        val liveEvents = fetchStreams().mapNotNull { it.toSearchResponse() }
        if (liveEvents.isNotEmpty()) {
            homeSections.add(HomePageList("Live Sports & Events", liveEvents))
        }

        // 2. All channels
        val allChannels = fetchChannels()
        if (allChannels.isNotEmpty()) {
            // Live Sports Channels
            val sportsChannels = allChannels.filter {
                it.sport?.contains("sport", ignoreCase = true) == true ||
                it.sport?.contains("wwe", ignoreCase = true) == true ||
                it.name?.contains("sport", ignoreCase = true) == true ||
                it.name?.contains("espn", ignoreCase = true) == true ||
                it.name?.contains("dazn", ignoreCase = true) == true
            }.mapNotNull { it.toSearchResponse() }
            if (sportsChannels.isNotEmpty()) {
                homeSections.add(HomePageList("Sports & Combat TV", sportsChannels))
            }

            // USA / Major Networks
            val usaChannels = allChannels.filter {
                it.region?.contains("usa", ignoreCase = true) == true ||
                it.flag?.equals("us", ignoreCase = true) == true
            }.mapNotNull { it.toSearchResponse() }
            if (usaChannels.isNotEmpty()) {
                homeSections.add(HomePageList("USA Live TV", usaChannels))
            }

            // UK & International
            val intlChannels = allChannels.filter {
                it.region?.contains("uk", ignoreCase = true) == true ||
                it.flag?.equals("gb", ignoreCase = true) == true
            }.mapNotNull { it.toSearchResponse() }
            if (intlChannels.isNotEmpty()) {
                homeSections.add(HomePageList("UK & International TV", intlChannels))
            }

            // Complete Channels Index
            val allMapped = allChannels.mapNotNull { it.toSearchResponse() }
            homeSections.add(HomePageList("All Channels", allMapped))
        }

        return newHomePageResponse(homeSections)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        val results = ArrayList<SearchResponse>()

        val events = fetchStreams()
        for (evt in events) {
            val nameMatch = evt.name?.lowercase()?.contains(q) == true
            val typeMatch = evt.type?.lowercase()?.contains(q) == true
            if (nameMatch || typeMatch) {
                evt.toSearchResponse()?.let { results.add(it) }
            }
        }

        val channels = fetchChannels()
        for (ch in channels) {
            val nameMatch = ch.name?.lowercase()?.contains(q) == true
            val sportMatch = ch.sport?.lowercase()?.contains(q) == true
            val tagMatch = ch.tagline?.lowercase()?.contains(q) == true
            val regionMatch = ch.region?.lowercase()?.contains(q) == true
            if (nameMatch || sportMatch || tagMatch || regionMatch) {
                ch.toSearchResponse()?.let { results.add(it) }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.trim()
        val isStream = cleanUrl.contains("/stream/")
        val slug = cleanUrl.substringAfterLast("/")

        if (isStream) {
            val streams = fetchStreams()
            val stream = streams.firstOrNull { it.id == slug }
            if (stream != null) {
                val title = stream.name ?: "Live Stream"
                val poster = stream.thumbnail?.takeIf { it.isNotBlank() } ?: DEFAULT_POSTER
                val sources = if (stream.source != null) listOf(stream.source) else emptyList()
                val passData = ZLivePassData(
                    id = stream.id ?: slug,
                    name = title,
                    sources = sources,
                    poster = poster,
                    tagline = stream.type
                ).toJson()

                return newMovieLoadResponse(title, url, TvType.Live, passData) {
                    this.posterUrl = poster
                    this.plot = "Live Event · " + (stream.type ?: "Sports")
                    this.tags = listOfNotNull(stream.type, "Live")
                }
            }
        }

        // Channel
        val channels = fetchChannels()
        val channel = channels.firstOrNull { it.id == slug } ?: channels.firstOrNull { it.id?.contains(slug) == true }
        if (channel != null) {
            val title = channel.name ?: "Live Channel"
            val poster = if (!channel.flag.isNullOrBlank()) {
                "https://flagcdn.com/w160/" + channel.flag.lowercase() + ".png"
            } else {
                DEFAULT_POSTER
            }
            val sources = channel.sources.orEmpty()
            val passData = ZLivePassData(
                id = channel.id ?: slug,
                name = title,
                sources = sources,
                poster = poster,
                tagline = channel.tagline
            ).toJson()

            val qualityBadge = channel.quality ?: "HD"
            val regionText = channel.region ?: "Global"
            val plot = (channel.tagline ?: title) + " · " + qualityBadge + " · " + regionText

            return newMovieLoadResponse(title, url, TvType.Live, passData) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOfNotNull(channel.sport, channel.region, "Live TV")
            }
        }

        // Fallback for direct URL
        val passData = ZLivePassData(
            id = slug,
            name = slug,
            sources = listOf(ZLiveSource(key = slug, label = "IPTV"))
        ).toJson()

        return newMovieLoadResponse(slug, url, TvType.Live, passData) {
            this.posterUrl = DEFAULT_POSTER
            this.plot = "Live Stream ($slug)"
            this.tags = listOf("Live")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val passData = try {
            parseJson<ZLivePassData>(data)
        } catch (e: Exception) {
            return false
        }

        var linksFound = false
        val sources = if (passData.sources.isNotEmpty()) {
            passData.sources
        } else {
            listOf(ZLiveSource(key = passData.id, label = "IPTV"))
        }

        val dateStr = getUtcDateString()

        for (src in sources) {
            val key = src.key ?: passData.id
            val serverLabel = src.label?.takeIf { it.isNotBlank() } ?: "Live Stream"

            try {
                val cleanSlug = key.removePrefix("auto-").removePrefix("evt-")
                val ts = System.currentTimeMillis() / 1000
                val payload = """{"slug":"$cleanSlug","ts":$ts}"""
                val encryptedBody = encryptPayload(payload, dateStr)

                val resText = app.post(
                    API_RESOLVE,
                    json = encryptedBody,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to USER_AGENT
                    )
                ).text

                val resolveRes = parseJson<ZLiveResolveResponse>(resText)
                val streamUrl = resolveRes.location?.trim()

                if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            source = serverLabel,
                            name = "${this.name} - $serverLabel",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    linksFound = true
                }
            } catch (e: Exception) {
                // Continue with other sources
            }
        }

        return linksFound
    }
}
