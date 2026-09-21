package com.uchiharepo.movienest

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class MovieNestProvider : MainAPI() {
    override var mainUrl = "https://movienestbd.best"
    override var name = "MovieNestBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Latest Movies",
        "$mainUrl/series?page=" to "Latest Series",
        "$mainUrl/category/hollywood?page=" to "Hollywood Movies",
        "$mainUrl/category/bollywood?page=" to "Bollywood Movies",
        "$mainUrl/category/south-indian?page=" to "South Indian",
        "$mainUrl/category/korean?page=" to "Korean & Asian",
        "$mainUrl/genre/animation?page=" to "Anime & Animation",
        "$mainUrl/language/bengali?page=" to "Bengali Content",
        "$mainUrl/language/dual-audio?page=" to "Dual Audio"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val home = document.select("a.movie-card").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.trim()}"
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        return document.select("a.movie-card").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") } ?: return null
        if (href.isBlank() || href.startsWith("#") || href.contains("javascript")) return null
        val fullUrl = fixUrl(href)

        val titleEl = this.selectFirst("h3")
        val title = titleEl?.ownText()?.trim()?.ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.ifEmpty { null }
            ?: titleEl?.text()?.trim()
            ?: return null

        val posterUrl = extractPoster(this)
        val year = this.selectFirst("h3 span")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val isSeries = this.text().contains("SERIES", ignoreCase = true) || href.contains("-s", ignoreCase = true) || href.contains("/series")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    private fun extractPoster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifEmpty {
            img.attr("data-lazy-src").ifEmpty {
                img.attr("src")
            }
        }
        return if (src.isNotBlank() && !src.startsWith("data:")) fixUrl(src) else null
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val title = document.selectFirst("h1, h2.title, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "MovieNestBD"

        val poster = extractPoster(document)
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("p.plot, .synopsis p, .entry-content p, meta[name='description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value?.toIntOrNull()
            ?: document.selectFirst(".year, span:contains(Release), span:contains(Year)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val genres = document.select("a[href*='/genre/'], a[href*='/category/'], .genres a").map {
            it.text().trim()
        }.filter { it.isNotBlank() }.distinct()

        val actors = document.select("a[href*='/actor/'], a[href*='/cast/'], .actors a, .cast a").map {
            it.text().trim()
        }.filter { it.isNotBlank() }.distinct()

        val rating = document.selectFirst(".rating, .imdb-rating, span:contains(IMDb)")?.text()
            ?.replace(Regex("[^0-9.]"), "")?.toRatingInt()

        val isSeries = url.contains("/series") || url.contains("-s") || document.select(".episodes a, a[href*='episode']").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val epElements = document.select(".episodes a, a.episode-card, a[href*='-episode-'], a[href*='-s'], .episode-list a")

            for ((idx, el) in epElements.withIndex()) {
                val epHref = el.attr("href").trim()
                if (epHref.isBlank() || epHref.startsWith("#")) continue
                val epTitle = el.selectFirst(".title, h4, span")?.text()?.trim()
                    ?: el.text().trim().ifEmpty { "Episode ${idx + 1}" }

                val epNum = Regex("""(?i)(?:ep|episode|e)\s*[-:]?\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                val seasonNum = Regex("""(?i)(?:s|season)\s*[-:]?\s*(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Full Episode / Stream"
                        this.episode = 1
                        this.season = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.rating = rating
                addActors(actors)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.rating = rating
                addActors(actors)
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

        // 1. Collect iframe embeds
        document.select("iframe[src], iframe[data-src]").forEach {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }.trim()
            if (src.isNotBlank()) embedUrls.add(src)
        }

        // 2. Collect quality & direct embed buttons
        document.select("a.btn-quality, a[href*='embed'], a[href*='jiofiles'], a[href*='xcloud']").forEach {
            val href = it.attr("href").trim()
            if (href.isNotBlank()) embedUrls.add(href)
        }

        val visitedUrls = mutableSetOf<String>()

        for (rawEmbed in embedUrls.distinct()) {
            val cleanUrl = fixUrl(rawEmbed)
            if (visitedUrls.contains(cleanUrl)) continue
            visitedUrls.add(cleanUrl)

            // Case 1: JioFiles Embed (https://embed.jiofiles.pics/...)
            if (cleanUrl.contains("jiofiles.pics")) {
                try {
                    val jioDoc = app.get(
                        cleanUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )
                    ).document

                    // Extract inner player iframe
                    val innerIframe = jioDoc.selectFirst("iframe#videoPlayer, iframe")?.attr("src")?.trim()
                    if (!innerIframe.isNullOrBlank()) {
                        val resolvedInner = fixUrl(innerIframe)
                        if (resolvePlayerUrl(resolvedInner, cleanUrl, subtitleCallback, callback)) {
                            loadedAny = true
                        }
                    }

                    // Extract all dropdown player servers: switchPlayer('url', 'type', 'name')
                    val scriptText = jioDoc.select("script").joinToString("\n") { it.data() }
                    val playerMatches = Regex("""switchPlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""").findAll(scriptText)
                    for (match in playerMatches) {
                        val pUrl = fixUrl(match.groupValues[1])
                        val pName = match.groupValues[3]
                        if (pUrl != innerIframe) {
                            if (resolvePlayerUrl(pUrl, cleanUrl, subtitleCallback, callback, pName)) {
                                loadedAny = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next embed
                }
            } else {
                // Direct player url
                if (resolvePlayerUrl(cleanUrl, data, subtitleCallback, callback)) {
                    loadedAny = true
                }
            }
        }

        return loadedAny
    }

    private suspend fun resolvePlayerUrl(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        serverName: String = "MovieNest Server"
    ): Boolean {
        var loaded = false

        // 1. SeekPlayer via indbd.pages.dev
        if (playerUrl.contains("indbd.pages.dev") || playerUrl.contains("seekplayer")) {
            try {
                val match = Regex("""(?:embed/|url=)([^&/]+)[/&](?:id=)?([a-zA-Z0-9_-]+)""").find(playerUrl)
                val domain = match?.groupValues?.getOrNull(1) ?: "sasknsjks.seekplayer.vip"
                val id = match?.groupValues?.getOrNull(2) ?: ""

                if (id.isNotBlank()) {
                    val apiUrl = "https://indbd.pages.dev/api/info?url=$domain&id=$id&referer=https://movienestbd.best/"
                    val apiRes = app.get(
                        apiUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://indbd.pages.dev/embed/$domain/$id"
                        )
                    ).text

                    val json = parseJson<IndbdInfoResponse>(apiRes)
                    if (json.success == true && json.data != null) {
                        // Subtitle
                        json.data.subtitle?.forEach { (lang, subUrl) ->
                            val cleanSub = fixUrl(subUrl.substringBefore("#"))
                            subtitleCallback(SubtitleFile(lang.uppercase(), cleanSub))
                        }

                        // Playlist sources
                        json.data.playlistSources?.forEach { source ->
                            val file = source.file ?: return@forEach
                            if (file.contains(".m3u8")) {
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = file,
                                    referer = "https://$domain/",
                                    headers = mapOf("Referer" to "https://$domain/")
                                ).forEach {
                                    callback(it)
                                    loaded = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue to loadExtractor fallback
            }
        }

        // 2. Direct HLS (.m3u8) check
        if (playerUrl.contains(".m3u8")) {
            M3u8Helper.generateM3u8(
                source = serverName,
                streamUrl = playerUrl,
                referer = referer,
                headers = mapOf("Referer" to referer)
            ).forEach {
                callback(it)
                loaded = true
            }
            return loaded
        }

        // 3. CloudStream standard extractors (XStream, Xcloud, etc.)
        try {
            if (loadExtractor(playerUrl, referer, subtitleCallback, callback)) {
                loaded = true
            }
        } catch (e: Exception) {
            // Ignore extraction errors
        }

        return loaded
    }

    data class IndbdInfoResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: IndbdData? = null
    )

    data class IndbdData(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("subtitle") val subtitle: Map<String, String>? = null,
        @JsonProperty("playlistSources") val playlistSources: List<IndbdSource>? = null
    )

    data class IndbdSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )
}
