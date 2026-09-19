package com.uchiharepo.cinevood

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class CinevoodProvider : MainAPI() {
    override var mainUrl = "https://cinevood.rocks"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
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

        val MIRRORS = listOf(
            "https://cinevood.rocks",
            "https://cinevood.net",
            "https://cinevood.guru",
            "https://cinevood.me.in"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Releases",
        "$mainUrl/bollywood/page/" to "Bollywood Movies",
        "$mainUrl/hollywood/page/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/south-dubbed/page/" to "South Hindi Dubbed",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/page/" to "Hollywood Hindi Dubbed",
        "$mainUrl/web-series/page/" to "Web Series",
        "$mainUrl/punjabi/page/" to "Punjabi Movies",
        "$mainUrl/bengali/page/" to "Bengali Movies",
        "$mainUrl/tv-shows/page/" to "TV Shows",
        "$mainUrl/others/page/" to "Others"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data.removeSuffix("page/")
        } else {
            "${request.data}$page/"
        }

        val doc = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).document
        } catch (e: Exception) {
            app.get("https://cinevood.net/", headers = mapOf("User-Agent" to USER_AGENT)).document
        }

        val home = doc.select("article.latestPost, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$cleanQuery"

        val doc = try {
            app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).document
        } catch (e: Exception) {
            app.get(
                "https://cinevood.net/?s=$cleanQuery",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).document
        }

        return doc.select("article.latestPost, article").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("header h2.title a, h2.title a, a.post-image") ?: return null
        val rawTitle = titleElement.attr("title").ifBlank { titleElement.text() }.trim()
        if (rawTitle.isBlank()) return null
        val href = titleElement.attr("href").ifBlank { this.selectFirst("a")?.attr("href") } ?: return null

        val img = this.selectFirst("div.featured-thumbnail img, img")
        val poster = img?.attr("src")?.ifBlank { img.attr("data-src") }

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("S0", ignoreCase = true) ||
                rawTitle.contains("Complete", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true) ||
                href.contains("web-series") ||
                href.contains("tv-shows")

        val cleanTitle = rawTitle.replace(Regex("""(?i)\s*CineVood.*"""), "").trim()

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).document
        } catch (e: Exception) {
            val fallback = url.replace("cinevood.rocks", "cinevood.net")
            app.get(fallback, headers = mapOf("User-Agent" to USER_AGENT)).document
        }

        val title = doc.selectFirst("h1.title, header h1, h1")?.text()?.replace(Regex("""(?i)\s*CineVood.*"""), "")?.trim()
            ?: doc.title().replace(Regex("""(?i)\s*CineVood.*"""), "").trim()

        val poster = doc.selectFirst("div.featured-thumbnail img, div.entry-content img, div.post-single-content img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }

        val plot = doc.selectFirst("div.entry-content p:matches((?i)storyline|synopsis|plot)")?.text()
            ?: doc.select("div.entry-content p").firstOrNull { it.text().length > 40 }?.text()

        val year = Regex("""\((19\d\d|20\d\d)\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val tags = doc.select("div.tags a, p.post-meta a, span.category a").map { it.text().trim() }.distinct()

        val isSeries = title.contains("Season", ignoreCase = true) ||
                title.contains("S0", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                url.contains("web-series") ||
                url.contains("tv-shows")

        val contentLinks = doc.select("div.entry-content a, div.post-single-content a, article a")
        val directServers = mutableListOf<CineServer>()
        val episodeMap = mutableMapOf<Int, MutableList<CineServer>>()

        for (link in contentLinks) {
            val href = link.attr("href").trim()
            val text = link.text().trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("telegram") || href.contains("facebook") || href.contains("twitter") || href.contains("whatsapp")) {
                continue
            }

            val epMatch = Regex("""(?i)(?:Episode|EP|E)[\s\-_]*0*(\d+)""").find(text)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: 1
                val list = episodeMap.getOrPut(epNum) { mutableListOf() }
                list.add(CineServer(name = text.ifBlank { "Episode $epNum" }, url = href))
            } else if (href.contains("hubcloud") || href.contains("fastdl") || href.contains("drive") || href.contains("gdflix") || href.contains("pixel") || href.contains("link") || href.contains("download")) {
                directServers.add(CineServer(name = text.ifBlank { "Download / Stream Server" }, url = href))
            }
        }

        if (isSeries && episodeMap.isNotEmpty()) {
            val episodes = episodeMap.map { (epNum, servers) ->
                val epData = CineEpisodeData(
                    name = "Episode $epNum",
                    servers = servers
                ).toJson()
                newEpisode(epData) {
                    this.name = "Episode $epNum"
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val passData = CineMovieData(
            title = title,
            servers = directServers.ifEmpty { listOf(CineServer("CineVood Server", url)) }
        ).toJson()

        return newMovieLoadResponse(title, url, TvType.Movie, passData) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = try {
            val movie = parseJson<CineMovieData>(data)
            movie.servers
        } catch (e: Exception) {
            try {
                val ep = parseJson<CineEpisodeData>(data)
                ep.servers
            } catch (ex: Exception) {
                emptyList()
            }
        }

        if (servers.isEmpty()) return false

        for (server in servers) {
            val serverUrl = server.url
            try {
                if (serverUrl.contains("hubcloud")) {
                    resolveHubCloud(serverUrl, callback)
                } else if (serverUrl.contains("pixeldrain.com")) {
                    val id = serverUrl.substringAfterLast("/")
                    val directUrl = "https://pixeldrain.com/api/file/$id"
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "PixelDrain Fast CDN (Direct)",
                            url = directUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                } else if (serverUrl.endsWith(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "CineVood HLS Master Stream",
                            url = serverUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                } else if (serverUrl.endsWith(".mp4") || serverUrl.endsWith(".mkv")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "CineVood Direct Video (MP4)",
                            url = serverUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P720.value
                        }
                    )
                } else {
                    loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Continue to next server gracefully
            }
        }
        return true
    }

    private suspend fun resolveHubCloud(hubUrl: String, callback: (ExtractorLink) -> Unit) {
        val hubDoc = try {
            app.get(
                hubUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).document
        } catch (e: Exception) {
            return
        }

        // 1. PixelDrain Direct High-Speed Stream
        val pixelLink = hubDoc.selectFirst("a[href*='pixeldrain.com']")?.attr("href")
        if (!pixelLink.isNullOrBlank()) {
            val id = pixelLink.substringAfterLast("/")
            val streamUrl = "https://pixeldrain.com/api/file/$id"
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "HubCloud -> PixelDrain 1080p CDN",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://pixeldrain.com/"
                    this.quality = Qualities.P1080.value
                }
            )
        }

        // 2. HubCloud Video Player Embed
        val videoEmbed = hubDoc.selectFirst("iframe[src*='video'], iframe[src*='embed']")?.attr("src")
        if (!videoEmbed.isNullOrBlank()) {
            val resolvedEmbed = if (videoEmbed.startsWith("//")) "https:$videoEmbed" else videoEmbed
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "HubCloud Stream Player (Embed)",
                    url = resolvedEmbed,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = hubUrl
                    this.quality = Qualities.P720.value
                }
            )
        }

        // 3. Fast Server / Direct CDN Download
        val fastLinks = hubDoc.select("a.btn, a[href*='download'], a[href*='r2.dev'], a[href*='workers.dev']")
        for (fLink in fastLinks) {
            val href = fLink.attr("href")
            val text = fLink.text()
            if (href.isNotBlank() && !href.startsWith("#") && !href.contains("javascript")) {
                val isM3u8 = href.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "HubCloud Fast Server (${text.ifBlank { "Direct" }})",
                        url = href,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = hubUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }
    }
}

data class CineServer(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String
)

data class CineMovieData(
    @JsonProperty("title") val title: String,
    @JsonProperty("servers") val servers: List<CineServer>
)

data class CineEpisodeData(
    @JsonProperty("name") val name: String,
    @JsonProperty("servers") val servers: List<CineServer>
)
