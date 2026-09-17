package com.uchiharepo.animesalt

import android.util.Base64
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
import java.net.URLDecoder
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

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

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

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
        
        val raw = (
            img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty {
                    img.attr("srcset").substringBefore(" ").ifEmpty {
                        img.attr("src")
                    }
                }
            }
        ).trim()

        if (raw.isBlank() || raw.startsWith("data:image")) return null
        val cleaned = if (raw.startsWith("//")) "https:$raw" else raw
        
        // Filter out site logo/fallback icons
        if (cleaned.contains("AnimeSalticon", ignoreCase = true) ||
            cleaned.contains("AnimeSaltLong", ignoreCase = true) ||
            cleaned.contains("cropped-", ignoreCase = true)
        ) {
            return null
        }

        // Upscale TMDB images to high-quality 500px posters
        return cleaned.replace("/w185/", "/w500/").replace("/w342/", "/w500/")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.replace("Image ", "")?.trim()
            ?: return null

        val href = this.selectFirst("a.lnk-blk, a")?.attr("href") ?: return null
        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/tag/")) return null

        val posterUrl = extractImageUrl(this)
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

        // Search for real poster on the page (ignore default site icon)
        val poster = extractImageUrl(document.selectFirst(".post-thumbnail img, figure img, .poster img"))
            ?: document.select("img").mapNotNull { extractImageUrl(it) }.firstOrNull { it.contains("tmdb.org") }
            ?: document.select("img").mapNotNull { extractImageUrl(it) }.firstOrNull()

        // Generate high-resolution backdrop if TMDB poster exists
        val backdrop = poster?.replace("/w500/", "/w1280/")?.replace("/w185/", "/w1280/")

        val plot = document.selectFirst(".entry-content p, .description p, meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val tags = document.select(".genres a, .category a, .tags a, .tag a").map { it.text().trim() }.distinct()
        val year = document.selectFirst(".entry-meta .year, .year")?.text()?.trim()?.toIntOrNull()

        val isMovie = url.contains("/movies/")
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
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
                val epThumb = extractImageUrl(el) ?: poster

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
                this.backgroundPosterUrl = backdrop
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

            // 1. Handle Multi-Language Base64 Player (e.g. multi-lang-plyr/player.php?data=...)
            if (cleanUrl.contains("multi-lang-plyr/player.php") || cleanUrl.contains("player.php?data=")) {
                try {
                    val rawData = cleanUrl.substringAfter("data=").substringBefore("&")
                    val decodedJson = String(Base64.decode(URLDecoder.decode(rawData, "UTF-8"), Base64.DEFAULT))
                    val langList = parseJson<List<MultiLangItem>>(decodedJson)

                    for (item in langList) {
                        val directLink = item.link ?: continue
                        try {
                            if (loadExtractor(directLink, data, subtitleCallback, callback)) loadedAny = true
                        } catch (e: Exception) {
                            // Continue to next language stream
                        }
                    }
                } catch (e: Exception) {
                    // Ignore malformed player parameters
                }
            } else if (cleanUrl.contains("/video/")) {
                // 2. Handle FirePlayer / AS-CDN Multi-Audio HLS Master Streams
                try {
                    val origin = Regex("""https?://[^/]+""").find(cleanUrl)?.value ?: continue
                    val hash = cleanUrl.substringAfterLast("/video/").substringBefore("?").substringBefore("/")
                    if (hash.isBlank()) continue

                    // Visit embed page to establish session
                    val embedRes = app.get(
                        cleanUrl,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    )
                    val cookie = embedRes.headers["set-cookie"]?.split(";")?.firstOrNull() ?: ""

                    // Call getVideo endpoint to obtain legitimate HLS master stream
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
                        // Fetch master playlist text to identify all embedded audio languages
                        val m3u8Content = try {
                            app.get(
                                streamUrl,
                                headers = mapOf(
                                    "Referer" to "$origin/",
                                    "User-Agent" to USER_AGENT
                                )
                            ).text
                        } catch (e: Exception) {
                            ""
                        }

                        // Extract audio languages from #EXT-X-MEDIA:TYPE=AUDIO
                        val audioLangs = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\n]*NAME="([^"]+)"""")
                            .findAll(m3u8Content)
                            .map { it.groupValues[1] }
                            .distinct()
                            .toList()

                        val langTag = if (audioLangs.isNotEmpty()) {
                            " [Multi-Audio: ${audioLangs.reversed().joinToString(", ")}]"
                        } else {
                            " [Multi-Audio]"
                        }

                        // Emit the Master M3U8 directly so ExoPlayer enables the Audio Track switcher menu
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "AnimeSalt$langTag (HLS Master - Switch in Player)",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$origin/"
                                this.headers = mapOf(
                                    "Referer" to "$origin/",
                                    "User-Agent" to USER_AGENT
                                )
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        loadedAny = true

                        // Also generate resolution sub-streams for slower connections
                        try {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                streamUrl = streamUrl,
                                referer = "$origin/",
                                quality = Qualities.Unknown.value,
                                headers = mapOf(
                                    "Referer" to "$origin/",
                                    "User-Agent" to USER_AGENT
                                ),
                                name = "AnimeSalt"
                            ).forEach { link ->
                                callback.invoke(link)
                                loadedAny = true
                            }
                        } catch (e: Exception) {
                            // Sub-stream generation optional
                        }
                    }
                } catch (e: Exception) {
                    // Skip failed server silently
                }
            } else {
                try {
                    if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) loadedAny = true
                } catch (e: Exception) {
                    // Ignore unsupported extractors
                }
            }
        }

        return loadedAny
    }

    data class MultiLangItem(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("link") val link: String? = null
    )

    data class AnimeSaltVideoResponse(
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("videoImage") val videoImage: String? = null
    )
}
