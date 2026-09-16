package com.uchiharepo.animedekho

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
        "$mainUrl/home/" to "Spotlight"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("/home/")) {
            if (page > 1) return newHomePageResponse(emptyList())
            request.data
        } else {
            "${request.data}$page/"
        }

        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("article.post, div.post").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
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
        return document.select("article.post, div.post").mapNotNull {
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

        val tags = document.select(".details-lst a, .genres a").map { it.text().trim() }
        val year = document.selectFirst(".entry-meta .year, .year")?.text()?.trim()?.toIntOrNull()

        val isMovie = url.contains("/movie-hindi/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = ArrayList<Episode>()
            val epElements = document.select("a[href*='/epi/']")

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

        val serverElements = document.select("ul.bx-lst li a[data-src], .server-list li a[data-src]")

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
                // 1. NeoCDN Server (High-Speed Multi-Resolution CDN)
                if (decodedUrl.contains("/aaa/myth/play.php")) {
                    extractNeoCdn(decodedUrl, serverName, data, callback)
                }
                // 2. VidStream / Direct Embed
                else if (decodedUrl.contains("/embed/")) {
                    extractVidStreamOrEmbed(decodedUrl, serverName, data, subtitleCallback, callback)
                }
                // 3. AnimeDekho TR Redirect Servers (Vidmoly, VidSrc, SRuby, Omega, VidCloud, MyCloud, MirrorBot)
                else if (decodedUrl.contains("trdekho=") || decodedUrl.contains("animedekho.app/?tr")) {
                    extractTrServer(decodedUrl, serverName, data, subtitleCallback, callback)
                }
                // 4. Any direct third party host
                else {
                    loadExtractor(decodedUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Ignore and continue with next server
            }
        }

        return true
    }

    // --- Extractor 1: NeoCDN ---
    private suspend fun extractNeoCdn(
        playUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(
                playUrl,
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            ).text

            val fetchId = Regex("""/aaa/myth/fetch\.php\?id=([a-zA-Z0-9_/-]+)""").find(html)?.groupValues?.get(1)
            if (fetchId != null) {
                val apiUrl = "https://animedekho.app/aaa/myth/fetch.php?id=$fetchId"
                val json = app.get(
                    apiUrl,
                    headers = mapOf("Referer" to playUrl, "User-Agent" to USER_AGENT)
                ).text

                val resObj = parseJson<NeoCdnResponse>(json)
                val worker = "https://jolly-salad-69ad.zenhashi.workers.dev/?url="

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
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // --- Extractor 2: VidStream & Embeds ---
    private suspend fun extractVidStreamOrEmbed(
        embedUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(
                embedUrl,
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            ).text

            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc != null) {
                if (iframeSrc.contains("blakiteapi.xyz")) {
                    extractBlakiteDirect(iframeSrc, serverName, embedUrl, callback)
                } else {
                    loadExtractor(iframeSrc, embedUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // --- Extractor 3: TR Server Router ---
    private suspend fun extractTrServer(
        trUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(
                trUrl,
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            ).text

            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return

            if (iframeSrc.contains("mirror.xerver.xyz")) {
                extractXerverVidSrc(iframeSrc, serverName, callback)
            } else if (iframeSrc.contains("blakiteapi.xyz")) {
                extractBlakiteDirect(iframeSrc, serverName, trUrl, callback)
            } else if (iframeSrc.contains("rubystm.com") || iframeSrc.contains("streamruby.com")) {
                extractStreamRuby(iframeSrc, serverName, trUrl, subtitleCallback, callback)
            } else if (iframeSrc.contains("vidmoly.biz") || iframeSrc.contains("vidmoly.to")) {
                extractVidmolyDirect(iframeSrc, serverName, trUrl, subtitleCallback, callback)
            } else {
                loadExtractor(iframeSrc, trUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // --- Sub-Extractor: VidSrc (mirror.xerver.xyz) ---
    private suspend fun extractXerverVidSrc(
        iframeSrc: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val encMatch = Regex("""url=([^&]+)""").find(iframeSrc)?.groupValues?.get(1) ?: return
            val apiUrl = "https://mirror.xerver.xyz/get/play.php?url=${URLEncoder.encode(encMatch, "UTF-8")}&fetch=1"
            val jsonText = app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to iframeSrc,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json"
                )
            ).text

            val res = parseJson<XerverResponse>(jsonText)
            val instantDl = res.results?.instant_dl?.url
            if (!instantDl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - $serverName",
                        name = "$name - $serverName (Google Cloud Fast)",
                        url = instantDl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://mirror.xerver.xyz/"
                        this.quality = Qualities.P720.value
                    }
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // --- Sub-Extractor: Blakite / Rumble Multi-Quality HLS ---
    private suspend fun extractBlakiteDirect(
        embedUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                embedUrl,
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            ).text

            val idMatch = Regex("""['"]id['"]\s*:\s*['"]([^'"]+)['"]""").find(response)
            val tmdbMatch = Regex("""['"]tmdbId['"]\s*:\s*['"]?(\d+)['"]?""").find(response)

            val apiUrl = if (idMatch != null && tmdbMatch != null) {
                "https://blakiteapi.xyz/api/get.php?id=${idMatch.groupValues[1]}&tmdbId=${tmdbMatch.groupValues[1]}"
            } else if (tmdbMatch != null) {
                "https://blakiteapi.xyz/api/get.php?tmdbId=${tmdbMatch.groupValues[1]}"
            } else null

            if (apiUrl != null) {
                val apiJson = app.get(
                    apiUrl,
                    headers = mapOf("Referer" to embedUrl, "User-Agent" to USER_AGENT)
                ).text

                val apiRes = parseJson<BlakiteApiResponse>(apiJson)
                val data = apiRes.data
                if (data?.dataId != null && data.ranges != null) {
                    val baseDURL = "https://hugh.cdn.rumble.cloud/video/"
                    val qualityCodes = listOf("oaa", "baa", "caa", "gaa", "haa")
                    val qualityLabels = listOf("240p", "360p", "480p", "720p", "1080p")

                    val rangeLines = data.ranges.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
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
                                    source = "$name - $serverName",
                                    name = "$name ($label - Multi-Audio)",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://blakiteapi.xyz/"
                                    this.quality = getQualityInt(label)
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // --- Sub-Extractor: StreamRuby ---
    private suspend fun extractStreamRuby(
        rubyUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = rubyUrl.substringAfterLast("/").substringBefore(".html").substringBefore("?")
            val postRes = app.post(
                "https://rubystm.com/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to code,
                    "auto" to "1",
                    "referer" to "$mainUrl/"
                ),
                headers = mapOf(
                    "Referer" to rubyUrl,
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            ).text

            val masterM3u8 = Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(postRes)?.groupValues?.get(1)
            if (masterM3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - $serverName",
                        name = "$name - $serverName (HLS)",
                        url = masterM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://rubystm.com/"
                        this.quality = Qualities.P720.value
                    }
                )
            } else {
                loadExtractor(rubyUrl, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            loadExtractor(rubyUrl, referer, subtitleCallback, callback)
        }
    }

    // --- Sub-Extractor: Vidmoly ---
    private suspend fun extractVidmolyDirect(
        vidmolyUrl: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(
                vidmolyUrl,
                headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            ).text

            val m3u8 = Regex("""file:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - $serverName",
                        name = "$name - $serverName (Vidmoly HD)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidmoly.biz/"
                        this.quality = Qualities.P720.value
                    }
                )
            } else {
                loadExtractor(vidmolyUrl, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            loadExtractor(vidmolyUrl, referer, subtitleCallback, callback)
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
