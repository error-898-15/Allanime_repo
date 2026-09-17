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
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?s=")) {
            "${request.data}$page"
        } else {
            "${request.data}$page/"
        }

        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val items = document.select("article.post, div.post, article.item, div.item, div.entry, article.entry")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h2.entry-title a, h2.title a, h3.title a, .post-title a, a[rel=bookmark]")
            ?: this.selectFirst("a[title]")
            ?: this.selectFirst("a")
            ?: return null

        val title = titleEl.text().trim().ifBlank { titleEl.attr("title").trim() }
        if (title.isBlank()) return null

        val href = fixUrlNull(titleEl.attr("href")) ?: return null

        val imgEl = this.selectFirst("img")
        val poster = imgEl?.let {
            val raw = it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank {
                    it.attr("src")
                }
            }
            if (raw.isNotBlank()) {
                val cleaned = if (raw.startsWith("//")) "https:$raw" else raw
                fixUrlNull(cleaned)
            } else null
        }

        val isMovie = title.contains("Movie", ignoreCase = true) || href.contains("/category/movies/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = app.get(
            searchUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        return document.select("article.post, div.post, article.item, div.item, div.entry")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val title = document.selectFirst("h1.entry-title, h1.title, h1")?.text()?.trim()
            ?: "RareAnimes Item"

        val poster = document.selectFirst(".featured-image img, .post-thumbnail img, article img")?.let {
            val raw = it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
            if (raw.isNotBlank()) {
                val cleaned = if (raw.startsWith("//")) "https:$raw" else raw
                fixUrlNull(cleaned)
            } else null
        }

        val plot = document.selectFirst(".entry-content p, .post-content p, #synopsis")?.text()?.trim()

        val episodeNames = mutableMapOf<Int, String>()
        val episodeServers = mutableMapOf<Int, MutableList<ServerItem>>()

        var currentLang = "Hindi"

        val contentElements = document.select(".entry-content > *, .post-content > *")
        var currentEpNum: Int? = null

        for (elem in contentElements) {
            val txt = elem.text().trim()

            if (txt.contains("Tamil", ignoreCase = true)) currentLang = "Tamil"
            else if (txt.contains("Telugu", ignoreCase = true)) currentLang = "Telugu"
            else if (txt.contains("Hindi", ignoreCase = true)) currentLang = "Hindi"
            else if (txt.contains("English", ignoreCase = true)) currentLang = "English"

            val epMatch = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(txt)
            if (epMatch != null) {
                currentEpNum = epMatch.groupValues[1].toIntOrNull()
                if (currentEpNum != null && !episodeNames.containsKey(currentEpNum)) {
                    episodeNames[currentEpNum] = txt
                }
            }

            val links = elem.select("a[href]")
            for (a in links) {
                val href = a.attr("href").trim()
                val serverName = a.text().trim().ifBlank { "Stream" }

                if (href.startsWith("http") && !href.contains("rareanimes.mov") && !href.contains("facebook.com") && !href.contains("telegram") && !href.contains("twitter.com")) {
                    val linkEpMatch = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(serverName)
                        ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(txt)
                    
                    val targetEp = linkEpMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentEpNum

                    if (targetEp != null) {
                        if (!episodeServers.containsKey(targetEp)) {
                            episodeServers[targetEp] = mutableListOf()
                        }
                        episodeServers[targetEp]?.add(
                            ServerItem(lang = currentLang, server = serverName, url = href)
                        )
                    }
                }
            }
        }

        if (episodeServers.isNotEmpty()) {
            val episodes = episodeServers.map { (epNum, servers) ->
                val epTitle = episodeNames[epNum]?.ifBlank { "Episode $epNum" } ?: "Episode $epNum"
                newEpisode(toJson(EpisodeData(servers.distinctBy { it.url }))) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode ?: 0 }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val allExternalLinks = document.select(".entry-content a[href], .post-content a[href]")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                val serverName = a.text().trim().ifBlank { "Player" }
                if (href.startsWith("http") && !href.contains("rareanimes.mov") && !href.contains("telegram") && !href.contains("facebook.com")) {
                    ServerItem(lang = currentLang, server = serverName, url = href)
                } else null
            }.distinctBy { it.url }

        if (allExternalLinks.isNotEmpty()) {
            val singleServers = allExternalLinks.take(15)
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, toJson(EpisodeData(singleServers))) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val iframes = document.select(".entry-content iframe[src], .post-content iframe[src], .video-container iframe[src]")
            .mapNotNull { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) {
                    val full = if (src.startsWith("//")) "https:$src" else src
                    ServerItem(lang = "Hindi", server = "Direct Embed", url = full)
                } else null
            }

        return newMovieLoadResponse(title, url, TvType.AnimeMovie, toJson(EpisodeData(iframes))) {
            this.posterUrl = poster
            this.plot = plot
        }
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
                listOf(ServerItem("Hindi", "Player", data))
            }
        } catch (e: Exception) {
            listOf(ServerItem("Hindi", "Player", data))
        }

        for (server in servers) {
            val rawUrl = server.url.trim()
            val cleanUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

            if (cleanUrl.contains("codedew.com/") || cleanUrl.contains("streambeta.xyz/") || cleanUrl.contains("linkstowatch.")) {
                try {
                    val res = app.get(
                        cleanUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )
                    )
                    val html = res.text

                    val fileMatch = Regex("""file:\s*["']([^"']+)["']""").find(html)
                        ?: Regex("""source:\s*["']([^"']+)["']""").find(html)
                        ?: Regex("""src:\s*["']([^"']+)["']""").find(html)

                    if (fileMatch != null) {
                        val streamUrl = fileMatch.groupValues[1].trim()
                        val isM3u8 = streamUrl.contains(".m3u8")

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "RareAnimes [${server.lang}] - ${server.server}",
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = cleanUrl
                                this.headers = mapOf(
                                    "Referer" to cleanUrl,
                                    "User-Agent" to USER_AGENT
                                )
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        loadedAny = true
                    }

                    val jsonSourcesMatch = Regex("""sources:\s*(\[[^\]]+\])""").find(html)
                    if (jsonSourcesMatch != null) {
                        val sourcesJson = jsonSourcesMatch.groupValues[1]
                        val parsedSources = try {
                            parseJson<List<PlayerSource>>(sourcesJson)
                        } catch (e: Exception) {
                            emptyList()
                        }

                        for (src in parsedSources) {
                            val streamUrl = src.url ?: src.streamUrl ?: continue
                            val srcName = src.name ?: server.server
                            val isM3u8 = streamUrl.contains(".m3u8")

                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "RareAnimes [${server.lang}] - $srcName",
                                    url = streamUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
                                    // optional
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
