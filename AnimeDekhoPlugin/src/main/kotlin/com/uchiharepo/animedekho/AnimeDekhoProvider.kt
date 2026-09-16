package com.uchiharepo.animedekho

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.app"
    override var name = "AnimeDekho"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/series-hindi/page/" to "Latest Series",
        "$mainUrl/movie-hindi/page/" to "Latest Movies",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/adventure/page/" to "Adventure",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/fantasy/page/" to "Fantasy",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/animation/page/" to "Animation",
        "$mainUrl/category/hindi-dub/page/" to "Hindi Dubbed",
        "$mainUrl/category/tamil/page/" to "Tamil",
        "$mainUrl/category/telugu/page/" to "Telugu",
        "$mainUrl/category/crunchyroll/page/" to "Crunchyroll",
        "$mainUrl/category/cartoon/page/" to "Cartoons"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("article.post, div.post, .film_list-wrap .flw-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title, .entry-title, .film-name a")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val href = this.selectFirst("a.lnk-blk, a")?.attr("href") ?: return null
        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/tag/")) return null

        val posterUrl = this.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        val isMovie = href.contains("/movie-hindi/") || this.hasClass("movie")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article.post, div.post, .film_list-wrap .flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "AnimeDekho"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img, figure img")?.attr("src")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content p, .description p")?.text()?.trim()

        val tags = document.select(".details-lst a, .genres a, .category a").map { it.text().trim() }
        val year = document.selectFirst(".entry-meta .year, .year")?.text()?.trim()?.toIntOrNull()

        val epElements = document.select("a[href*='/epi/']")
        val isMovie = url.contains("/movie-hindi/") || epElements.isEmpty()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = ArrayList<Episode>()
            epElements.forEach { element ->
                val epHref = element.attr("href")
                val epText = element.text().trim()
                if (epHref.isNotBlank() && !epText.contains("Latest Episode", ignoreCase = true)) {
                    val seasonEpRegex = Regex("""(\d+)x(\d+)""")
                    val match = seasonEpRegex.find(epHref)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "Season $season Episode $epNum"
                            this.season = season
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            }

            val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(
                compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 }
            )

            return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val serverElements = document.select("a[data-src], ul.bx-lst li a[data-src], .server-list li a[data-src]")
        var totalLinksLoaded = 0

        for (srv in serverElements) {
            val serverName = srv.selectFirst(".num, span")?.text()?.trim() ?: "Server"
            val b64 = srv.attr("data-src").trim()
            if (b64.isBlank()) continue

            val decodedUrl = try {
                val decodedBytes = Base64.decode(b64, Base64.DEFAULT)
                String(decodedBytes, StandardCharsets.UTF_8).trim()
            } catch (e: Exception) {
                null
            } ?: continue

            try {
                // 1. NeoCDN (1080p, 720p, 360p)
                if (decodedUrl.contains("/aaa/myth/play.php")) {
                    if (extractNeoCdn(decodedUrl, serverName, data, callback)) totalLinksLoaded++
                }
                // 2. VidStream Direct Embed
                else if (decodedUrl.contains("/embed/")) {
                    if (extractVidStreamOrEmbed(decodedUrl, serverName, data, subtitleCallback, callback)) totalLinksLoaded++
                }
                // 3. AnimeDekho TR Redirect Servers (Blakite, VidSrc, Vidmoly, Omega, Abyss)
                else if (decodedUrl.contains("trdekho=") || decodedUrl.contains("animedekho.app/?tr")) {
                    if (extractTrServer(decodedUrl, serverName, data, subtitleCallback, callback)) totalLinksLoaded++
                }
                // 4. Default LoadExtractor
                else {
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                    totalLinksLoaded++
                }
            } catch (e: Exception) {
                // Ignore and proceed
            }
        }

        return totalLinksLoaded > 0 || serverElements.isNotEmpty()
    }

    // --- Server 1: NeoCDN ---
    private suspend fun extractNeoCdn(
        playUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(playUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text
            val fetchId = Regex("""/aaa/myth/fetch\.php\?id=([a-zA-Z0-9_\/-]+)""").find(html)?.groupValues?.get(1)
            if (fetchId != null) {
                val apiUrl = "https://animedekho.app/aaa/myth/fetch.php?id=$fetchId"
                val json = app.get(apiUrl, headers = mapOf("Referer" to playUrl, "User-Agent" to USER_AGENT)).text
                val resObj = parseJson<NeoCdnResponse>(json)
                val worker = "https://jolly-salad-69ad.zenhashi.workers.dev/?url="

                var added = false
                resObj.sources?.forEach { s ->
                    if (!s.url.isNullOrBlank()) {
                        val proxiedUrl = worker + URLEncoder.encode(s.url, "UTF-8")
                        val label = s.type ?: "720p"
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - NeoCDN",
                                name = "$name - NeoCDN ($label)",
                                url = proxiedUrl,
                                type = if (s.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = getQualityInt(label)
                            }
                        )
                        added = true
                    }
                }
                added
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // --- Server 2: VidStream & Embeds ---
    private suspend fun extractVidStreamOrEmbed(
        embedUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc != null) {
                val fullIframe = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                if (fullIframe.contains("blakiteapi.xyz")) {
                    extractBlakiteDirect(fullIframe, serverName, embedUrl, callback)
                } else {
                    loadExtractor(fullIframe, embedUrl, subtitleCallback, callback)
                    true
                }
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // --- Server 3: All TR Server Handlers ---
    private suspend fun extractTrServer(
        trUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(trUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return false
            val fullIframe = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

            when {
                fullIframe.contains("mirror.xerver.xyz") -> extractXerverVidSrc(fullIframe, serverName, callback)
                fullIframe.contains("blakiteapi.xyz") -> extractBlakiteDirect(fullIframe, serverName, trUrl, callback)
                fullIframe.contains("vidmoly.biz") || fullIframe.contains("vidmoly.to") -> extractVidmolyDirect(fullIframe, serverName, trUrl, subtitleCallback, callback)
                fullIframe.contains("emturbovid.com") -> extractOmegaDirect(fullIframe, serverName, trUrl, callback)
                fullIframe.contains("abyssplayer.com") || fullIframe.contains("short.ink") -> extractAbyssDirect(fullIframe, serverName, trUrl, callback)
                else -> {
                    loadExtractor(fullIframe, trUrl, subtitleCallback, callback)
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    // --- Server 4: VidSrc (Google Cloud 1080p, 720p, 480p) ---
    private suspend fun extractXerverVidSrc(
        iframeSrc: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val encMatch = Regex("""url=([^&]+)""").find(iframeSrc)?.groupValues?.get(1) ?: return false
            val apiUrl = "https://mirror.xerver.xyz/get/play.php?url=${URLEncoder.encode(encMatch, "UTF-8")}&fetch=1"
            val jsonText = app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to "https://mirror.xerver.xyz/get/play.php",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json"
                )
            ).text

            val res = parseJson<XerverResponse>(jsonText)
            val instantDl = res.results?.instant_dl?.url
            if (!instantDl.isNullOrBlank()) {
                val qualityLabel = if (serverName.contains("480")) "480p" else if (serverName.contains("720")) "720p" else "1080p"
                callback.invoke(
                    newExtractorLink(
                        source = "$name - VidSrc",
                        name = "$name - $serverName (Google Cloud $qualityLabel)",
                        url = instantDl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://mirror.xerver.xyz/"
                        this.quality = getQualityInt(qualityLabel)
                    }
                )
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // --- Server 5: Blakite / Rumble Multi-Audio HLS (5 Qualities) ---
    private suspend fun extractBlakiteDirect(
        embedUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = embedUrl.substringAfter("/embed/").substringBefore("?").split("/").filter { it.isNotBlank() }
            val tmdbId = parts.getOrNull(0)
            val uniqueId = parts.getOrNull(1)

            val apiUrl = if (!tmdbId.isNullOrBlank() && !uniqueId.isNullOrBlank()) {
                "https://blakiteapi.xyz/api/get.php?id=$uniqueId&tmdbId=$tmdbId"
            } else if (!tmdbId.isNullOrBlank()) {
                "https://blakiteapi.xyz/api/get.php?tmdbId=$tmdbId"
            } else {
                null
            }

            if (apiUrl != null) {
                val apiJson = app.get(
                    apiUrl,
                    headers = mapOf(
                        "Referer" to "https://blakiteapi.xyz/",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text

                val apiRes = parseJson<BlakiteApiResponse>(apiJson)
                val data = apiRes.data
                if (data?.dataId != null && data.ranges != null) {
                    val baseDURL = "https://hugh.cdn.rumble.cloud/video/"
                    val qualityCodes = listOf("oaa", "baa", "caa", "gaa", "haa")
                    val qualityLabels = listOf("240p", "360p", "480p", "720p", "1080p")

                    val rangeLines = data.ranges.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    var found = false
                    for (line in rangeLines) {
                        val match = Regex("""^(\d+-\d+)\s*\(([^)]+)\)""").find(line)
                        if (match != null) {
                            val range = match.groupValues[1]
                            val label = match.groupValues[2].trim()
                            val idx = qualityLabels.indexOf(label)
                            val code = if (idx >= 0) qualityCodes[idx] else "gaa"
                            val streamUrl = "${baseDURL}${data.dataId}.${code}.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"

                            callback.invoke(
                                newExtractorLink(
                                    source = "$name - MultiAudio",
                                    name = "$name - MultiAudio ($label)",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://blakiteapi.xyz/"
                                    this.quality = getQualityInt(label)
                                }
                            )
                            found = true
                        }
                    }
                    found
                } else false
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // --- Server 6: Vidmoly ---
    private suspend fun extractVidmolyDirect(
        vidmolyUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(vidmolyUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text
            val m3u8 = Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(html)?.groupValues?.get(1)

            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Vidmoly",
                        name = "$name - Vidmoly (HD)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidmoly.biz/"
                        this.quality = Qualities.P720.value
                    }
                )
                true
            } else {
                loadExtractor(vidmolyUrl, referer, subtitleCallback, callback)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // --- Server 7: Omega (Emturbovid) ---
    private suspend fun extractOmegaDirect(
        omegaUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(omegaUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text
            val m3u8 = Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(html)?.groupValues?.get(1)
            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Omega",
                        name = "$name - Omega (Fast HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://emturbovid.com/"
                        this.quality = Qualities.P720.value
                    }
                )
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // --- Server 8: HydraX / Abyss Player ---
    private suspend fun extractAbyssDirect(
        abyssUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(abyssUrl, headers = mapOf("Referer" to "https://animedekho.app/", "User-Agent" to USER_AGENT)).text
            val videoSrc = Regex("""source\s*src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoSrc != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - HydraX",
                        name = "$name - HydraX (Abyss)",
                        url = videoSrc,
                        type = if (videoSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = abyssUrl
                        this.quality = Qualities.P720.value
                    }
                )
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun getQualityInt(quality: String): Int {
        return when {
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            quality.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    // --- Data Classes ---
    data class NeoCdnResponse(
        val final_url: String? = null,
        val sources: List<NeoCdnSource>? = null
    )

    data class NeoCdnSource(
        val url: String? = null,
        val type: String? = null,
        val size: String? = null
    )

    data class XerverResponse(
        val results: XerverResults? = null,
        val cached: Boolean? = null
    )

    data class XerverResults(
        val instant_dl: XerverItem? = null
    )

    data class XerverItem(
        val label: String? = null,
        val url: String? = null
    )

    data class BlakiteApiResponse(
        val success: Boolean? = null,
        val data: BlakiteApiData? = null
    )

    data class BlakiteApiData(
        val animeTitle: String? = null,
        val dataId: String? = null,
        val ranges: String? = null,
        val format: String? = null
    )

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
