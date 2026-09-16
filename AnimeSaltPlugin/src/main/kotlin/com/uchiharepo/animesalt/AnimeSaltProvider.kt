package com.uchiharepo.animesalt

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSaltProvider : MainAPI() {
    override var mainUrl = "https://animesalt.cx"
    override var name = "AnimeSalt"
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
        "$mainUrl/series/page/" to "Latest Series",
        "$mainUrl/movies/page/" to "Latest Movies",
        "$mainUrl/category/anime/page/" to "Anime Series",
        "$mainUrl/category/cartoon/page/" to "Cartoons",
        "$mainUrl/category/network/disney-channel/page/" to "Disney Channel",
        "$mainUrl/category/network/cartoon-network/page/" to "Cartoon Network",
        "$mainUrl/category/network/hungama-tv/page/" to "Hungama TV",
        "$mainUrl/category/network/crunchyroll/page/" to "Crunchyroll",
        "$mainUrl/category/network/netflix/page/" to "Netflix",
        "$mainUrl/category/network/prime-video/page/" to "Prime Video",
        "$mainUrl/category/network/sony-yay/page/" to "Sony YAY"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.replace("Image ", "")?.trim()
            ?: return null

        val href = this.selectFirst("a.lnk-blk, a")?.attr("href") ?: return null
        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/tag/")) return null

        val posterUrl = this.selectFirst("img")?.let {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            fixUrl(src)
        }

        val isMovie = href.contains("/movies/")
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
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "AnimeSalt"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img, figure img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }?.let { fixUrl(it) }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content p, .description p")?.text()?.trim()

        val tags = document.select(".genres a, .category a, .tags a, .tag a").map { it.text().trim() }.distinct()
        val year = document.selectFirst(".entry-meta .year, .year")?.text()?.trim()?.toIntOrNull()

        val isMovie = url.contains("/movies/")
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = ArrayList<Episode>()
            val epElements = document.select("article.episodes, li:has(a[href*='/episode/']), a[href*='/episode/']")
            
            for (el in epElements) {
                val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/episode/']") ?: continue
                val epHref = linkEl.attr("href").trim()
                if (epHref.isBlank() || !epHref.contains("/episode/")) continue

                val epName = el.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
                    ?: linkEl.text().replace(Regex("First|Latest Dub|View", RegexOption.IGNORE_CASE), "").trim()

                val epThumb = el.selectFirst("img")?.let {
                    val src = it.attr("data-src").ifEmpty { it.attr("src") }
                    fixUrl(src)
                } ?: poster

                // Extract season and episode numbering (e.g., 1x2, S01E02)
                val match = Regex("""(?:[-_/])(\d+)x(\d+)""").find(epHref)
                    ?: Regex("""(?:S|Season\s*)(\d+)\s*(?:E|Episode\s*|x)(\d+)""", RegexOption.IGNORE_CASE).find(epName)
                
                val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

                val displayName = if (epName.isNotBlank() && !epName.equals("S${season}E${epNum}", ignoreCase = true)) {
                    epName
                } else {
                    "Season $season Episode $epNum"
                }

                episodes.add(
                    newEpisode(epHref) {
                        this.name = displayName
                        this.season = season
                        this.episode = epNum
                        this.posterUrl = epThumb
                    }
                )
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

        var loadedAny = false
        val embedUrls = mutableListOf<String>()

        document.select("iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.isNotBlank()) embedUrls.add(src)
        }
        document.select("[data-src]").forEach {
            val src = it.attr("data-src").trim()
            if (src.isNotBlank() && (src.contains("/video/") || src.contains("embed") || src.contains("player"))) {
                embedUrls.add(src)
            }
        }

        for (rawEmbed in embedUrls.distinct()) {
            val cleanUrl = if (rawEmbed.startsWith("//")) "https:$rawEmbed" else rawEmbed

            if (cleanUrl.contains("/video/")) {
                try {
                    val origin = Regex("""https?://[^/]+""").find(cleanUrl)?.value ?: continue
                    val hash = cleanUrl.substringAfterLast("/video/").substringBefore("?").substringBefore("/")
                    if (hash.isBlank()) continue

                    // 1. Visit original embed page to establish FirePlayer session cookies
                    val embedRes = app.get(
                        cleanUrl,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    )
                    val cookie = embedRes.headers["set-cookie"]?.split(";")?.firstOrNull() ?: ""

                    // 2. Call getVideo endpoint to obtain legitimate HLS master stream
                    val getVideoUrl = "$origin/player/index.php?data=$hash&do=getVideo"
                    val postHeaders = mutableMapOf(
                        "Referer" to cleanUrl,
                        "User-Agent" to USER_AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                    if (cookie.isNotBlank()) {
                        postHeaders["Cookie"] = cookie
                    }

                    val postRes = app.post(
                        getVideoUrl,
                        headers = postHeaders,
                        data = mapOf(
                            "hash" to hash,
                            "r" to "$mainUrl/"
                        )
                    )

                    val videoData = try {
                        parseJson<AnimeSaltVideoResponse>(postRes.text)
                    } catch (e: Exception) {
                        null
                    }

                    val streamUrl = videoData?.videoSource ?: videoData?.securedLink
                    if (!streamUrl.isNullOrBlank()) {
                        var m3u8Generated = false
                        try {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                streamUrl = streamUrl,
                                referer = "$origin/",
                                headers = mapOf(
                                    "Referer" to "$origin/",
                                    "User-Agent" to USER_AGENT
                                ),
                                name = "AnimeSalt CDN"
                            ).forEach { link ->
                                callback.invoke(link)
                                m3u8Generated = true
                                loadedAny = true
                            }
                        } catch (e: Exception) {
                            // Fallback to direct master link
                        }

                        if (!m3u8Generated) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "AnimeSalt CDN (Multi-Audio HLS)",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$origin/"
                                    this.headers = mapOf(
                                        "Referer" to "$origin/",
                                        "User-Agent" to USER_AGENT
                                    )
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            loadedAny = true
                        }
                    }
                } catch (e: Exception) {
                    // Skip failed server silently
                }
            } else {
                try {
                    loadExtractor(cleanUrl, data, subtitleCallback, callback)
                    loadedAny = true
                } catch (e: Exception) {
                    // Ignore unsupported extractors
                }
            }
        }

        return loadedAny
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    data class AnimeSaltVideoResponse(
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("videoImage") val videoImage: String? = null
    )
}
