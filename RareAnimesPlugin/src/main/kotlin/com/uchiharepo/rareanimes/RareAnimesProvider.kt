package com.uchiharepo.rareanimes

import android.util.Base64
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
import java.net.URLEncoder

class RareAnimesProvider : MainAPI() {
    override var mainUrl = "https://www.rareanimes.mov"
    override var name = "RareAnimes"
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
        "$mainUrl/page/" to "Latest Releases",
        "$mainUrl/category/movies/page/" to "Movies",
        "$mainUrl/?s=Hindi&paged=" to "Hindi Dubbed",
        "$mainUrl/?s=Naruto&paged=" to "Naruto Series",
        "$mainUrl/?s=Pokemon&paged=" to "Pokemon",
        "$mainUrl/?s=Dragon+Ball&paged=" to "Dragon Ball",
        "$mainUrl/?s=Doraemon&paged=" to "Doraemon",
        "$mainUrl/?s=Shin+Chan&paged=" to "Shin Chan",
        "$mainUrl/?s=Ben+10&paged=" to "Ben 10",
        "$mainUrl/?s=Transformers&paged=" to "Transformers"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("?s=")) {
            "${request.data}$page"
        } else {
            "${request.data}$page/"
        }
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val home = document.select("article.post, article.herald-lay-b, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
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

        if (raw.isBlank() || raw.startsWith("data:image") || raw.contains("avatar")) return null
        return if (raw.startsWith("//")) "https:$raw" else raw
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h2.entry-title a, .entry-title a, h2 a, a[title]")
        val title = titleEl?.text()?.trim()
            ?: titleEl?.attr("title")?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val href = titleEl.attr("href").ifEmpty {
            this.selectFirst(".herald-post-thumbnail a, a")?.attr("href")
        }?.trim() ?: return null

        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/tag/") || href.contains("#")) return null

        val posterUrl = extractImageUrl(this)
        val isMovie = href.contains("/movie", ignoreCase = true) || title.contains("movie", ignoreCase = true)

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
        return document.select("article.post, article.herald-lay-b, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "RareAnimes"

        val poster = extractImageUrl(document.selectFirst(".entry-content img, .herald-post-thumbnail img, figure img"))
            ?: document.select("img").mapNotNull { extractImageUrl(it) }.firstOrNull()

        val plot = document.selectFirst(".entry-content p, meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val year = Regex("""Release Year:\s*.*?(\d{4})""", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select(".entry-content p:contains(Genre), .entry-meta .tag, .entry-tags a").map {
            it.text().replace(Regex("Genre:|🎭", RegexOption.IGNORE_CASE), "").trim()
        }.filter { it.isNotBlank() }

        val contentHtml = document.selectFirst(".entry-content")?.html() ?: document.html()
        val isMovie = url.contains("/movie", ignoreCase = true) || title.contains("movie", ignoreCase = true)

        val hasEpisodes = contentHtml.contains("Episode", ignoreCase = true) &&
                (contentHtml.contains("Episode 01", ignoreCase = true) || contentHtml.contains("Episode 1", ignoreCase = true))

        if (isMovie && !hasEpisodes) {
            val servers = extractServersFromHtml(contentHtml)
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, toJson(EpisodeData(servers))) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            val blocks = contentHtml.split(Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE))
            val episodeMap = mutableMapOf<Int, MutableList<ServerItem>>()
            val episodeNames = mutableMapOf<Int, String>()

            for (block in blocks) {
                val epMatch = Regex("""Episode\s+(\d+)(?:\s*[–-]\s*([^<\n]+))?""", RegexOption.IGNORE_CASE).find(block)
                    ?: continue
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                val rawName = epMatch.groupValues.getOrNull(2)?.trim() ?: ""
                val cleanName = rawName.replace(Regex("""<[^>]+>"""), "").trim()

                if (cleanName.isNotBlank() && (!episodeNames.containsKey(epNum) || episodeNames[epNum]?.contains("Untouched", ignoreCase = true) == true)) {
                    if (!cleanName.contains("Untouched", ignoreCase = true)) {
                        episodeNames[epNum] = cleanName
                    }
                }

                val serversInBlock = extractServersFromHtml(block)
                if (serversInBlock.isNotEmpty()) {
                    val list = episodeMap.getOrPut(epNum) { mutableListOf() }
                    list.addAll(serversInBlock)
                }
            }

            if (episodeMap.isEmpty()) {
                val singleServers = extractServersFromHtml(contentHtml)
                if (singleServers.isNotEmpty()) {
                    return newMovieLoadResponse(title, url, TvType.AnimeMovie, toJson(EpisodeData(singleServers))) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = tags
                    }
                }
            }

            val episodes = episodeMap.map { (epNum, servers) ->
                val epTitle = episodeNames[epNum]?.ifBlank { "Episode $epNum" } ?: "Episode $epNum"
                newEpisode(toJson(EpisodeData(servers.distinctBy { it.url }))) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode ?: 1 }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private fun extractServersFromHtml(html: String): List<ServerItem> {
        val servers = mutableListOf<ServerItem>()
        val pBlocks = Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
        val checkBlocks = if (pBlocks.isNotEmpty()) pBlocks else listOf(html)

        for (p in checkBlocks) {
            val lowerP = p.lowercase()
            val lang = when {
                lowerP.contains("hindi") -> "Hindi"
                lowerP.contains("tamil") -> "Tamil"
                lowerP.contains("telugu") -> "Telugu"
                lowerP.contains("bengali") -> "Bengali"
                lowerP.contains("malayalam") -> "Malayalam"
                lowerP.contains("english") -> "English"
                lowerP.contains("japanese") -> "Japanese"
                lowerP.contains("multi audio") || lowerP.contains("dual audio") -> "Multi-Audio"
                else -> "Hindi"
            }

            val linkMatches = Regex("""<a[^>]+href=["'](https?://[^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(p)
            for (lm in linkMatches) {
                val href = lm.groupValues[1].trim()
                val text = lm.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                if (href.contains("codedew.com") || href.contains("stream") || href.contains("mega.nz") || href.contains("drive.google")) {
                    val serverName = when {
                        text.contains("StreamBeta", ignoreCase = true) -> "StreamBeta"
                        text.contains("WatchMult", ignoreCase = true) || text.contains("MultiQuality", ignoreCase = true) -> "WatchMultiQuality"
                        text.contains("DLBeta", ignoreCase = true) -> "DLBeta"
                        text.contains("Mega", ignoreCase = true) -> "Mega"
                        text.isNotBlank() -> text
                        else -> "StreamBeta"
                    }
                    servers.add(ServerItem(lang = lang, server = serverName, url = href))
                }
            }
        }
        return servers.distinctBy { it.url }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var loadedAny = false
        val servers = try {
            if (data.startsWith("{")) {
                parseJson<EpisodeData>(data).servers
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        if (servers.isEmpty()) return false

        for (server in servers) {
            val cleanUrl = server.url.trim()

            if (cleanUrl.contains("codedew.com")) {
                try {
                    val res = app.get(
                        cleanUrl,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        )
                    )
                    val html = res.text

                    val playerSourcesMatch = Regex("""playerSources\s*=\s*(\[[^;]+\])""").find(html)
                    if (playerSourcesMatch != null) {
                        val sources = try {
                            parseJson<List<PlayerSource>>(playerSourcesMatch.groupValues[1])
                        } catch (e: Exception) {
                            emptyList()
                        }

                        for (src in sources) {
                            val streamUrl = src.streamUrl ?: src.url ?: continue
                            val srcName = src.name ?: "Stream"

                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "RareAnimes [${server.lang}] - $srcName",
                                    url = streamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://codedew.com/"
                                    this.headers = mapOf(
                                        "Referer" to "https://codedew.com/",
                                        "User-Agent" to USER_AGENT
                                    )
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            loadedAny = true

                            if (streamUrl.contains("workers.dev/")) {
                                try {
                                    val b64 = streamUrl.substringAfter("workers.dev/").substringBefore("?").trim()
                                    val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                                    val decoded = String(Base64.decode(padded, Base64.DEFAULT))
                                    val innerUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1)?.replace("\\/", "/")
                                    if (!innerUrl.isNullOrBlank()) {
                                        if (innerUrl.contains("pixeldra.in")) {
                                            val directPixel = innerUrl.replace("/u/", "/api/file/")
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = this.name,
                                                    name = "RareAnimes [${server.lang}] - Pixeldrain",
                                                    url = directPixel,
                                                    type = ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = "https://pixeldra.in/"
                                                    this.quality = Qualities.Unknown.value
                                                }
                                            )
                                            loadedAny = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    // optional fallback
                                }
                            }

                            val directSrcUrl = src.url
                            if (!directSrcUrl.isNullOrBlank() && directSrcUrl.contains("pixeldra.in")) {
                                val directPixel = directSrcUrl.replace("/u/", "/api/file/")
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "RareAnimes [${server.lang}] - Pixeldrain Fast",
                                        url = directPixel,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://pixeldra.in/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                loadedAny = true
                            }
                        }
                    }

                    val iframe = res.document.selectFirst("iframe[src]")?.attr("src")?.trim()
                    if (!iframe.isNullOrBlank()) {
                        val fullIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe
                        try {
                            if (loadExtractor(fullIframe, "https://codedew.com/", subtitleCallback, callback)) {
                                loadedAny = true
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            } else {
                try {
                    if (loadExtractor(cleanUrl, "$mainUrl/", subtitleCallback, callback)) {
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        return loadedAny
    }

    data class EpisodeData(
        @JsonProperty("servers") val servers: List<ServerItem> = emptyList()
    )

    data class ServerItem(
        @JsonProperty("lang") val lang: String,
        @JsonProperty("server") val server: String,
        @JsonProperty("url") val url: String
    )

    data class PlayerSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null,
        @JsonProperty("name") val name: String? = null
    )
}
