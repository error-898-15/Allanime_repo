package com.uchiharepo.rareanimes

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URLDecoder

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
        TvType.Movie
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/home/page/" to "Latest Releases",
        "$mainUrl/category/movies/page/" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            if (request.data.contains("/home/")) "$mainUrl/home/"
            else request.data.removeSuffix("page/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(targetUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = document.select("article.herald-post, article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article.herald-post, article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a") ?: this.selectFirst("a[title]") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        if (title.isBlank() || href.isBlank()) return null

        val posterElement = this.selectFirst(".herald-post-thumbnail img") ?: this.selectFirst("img")
        val posterUrl = posterElement?.attr("srcset")?.split(",")?.lastOrNull()?.trim()?.substringBefore(" ")
            ?: posterElement?.attr("src")
            ?: posterElement?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".herald-post-thumbnail img")?.attr("src")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()?.trim()

        val isMovie = url.contains("/movies/") || title.contains("Movie", ignoreCase = true)
        val content = document.selectFirst(".entry-content, .herald-entry-content")

        val episodeList = mutableListOf<Episode>()

        if (content != null) {
            val pAndHeadingElements = content.select("p, h3, h4")
            var currentEpName: String? = null
            var currentEpNum: Int? = null
            val currentServers = mutableListOf<ServerLink>()

            fun flushCurrentEpisode() {
                if (currentServers.isNotEmpty()) {
                    val epName = currentEpName ?: "Episode ${episodeList.size + 1}"
                    episodeList.add(
                        newEpisode(toJson(EpisodeData(currentServers.toList()))) {
                            this.name = epName
                            this.episode = currentEpNum ?: (episodeList.size + 1)
                        }
                    )
                    currentServers.clear()
                }
            }

            for (element in pAndHeadingElements) {
                val text = element.text().trim()
                val links = element.select("a[href]").mapNotNull { linkEl ->
                    val href = linkEl.attr("href").trim()
                    val rawName = linkEl.text().trim()
                    if (href.contains("codedew.com") || href.contains("store.animetoonhindi.com") ||
                        rawName.contains("Watch", ignoreCase = true) || rawName.contains("Stream", ignoreCase = true) ||
                        rawName.contains("Mega", ignoreCase = true) || rawName.contains("Beta", ignoreCase = true)
                    ) {
                        ServerLink(name = rawName, url = href)
                    } else null
                }

                // Check for Episode Header
                val epHeaderRegex = Regex("""^(?:Episode|Ep\b|\d+\.)\s*(\d+)""", RegexOption.IGNORE_CASE)
                val epMatch = epHeaderRegex.find(text)

                if (epMatch != null && links.none { it.name.contains("Watch", true) || it.name.contains("Stream", true) }) {
                    flushCurrentEpisode()
                    currentEpNum = epMatch.groupValues[1].toIntOrNull()
                    currentEpName = text
                } else if (links.isNotEmpty()) {
                    // Extract language tag from line prefix
                    val langPrefixMatch = Regex("""^([A-Za-z\s\(\)]+)\s*[-–]""").find(text)
                    val langTag = langPrefixMatch?.groupValues?.get(1)?.trim()?.takeIf {
                        !it.contains("Episode", ignoreCase = true) && !it.contains("Watch", ignoreCase = true)
                    }

                    // Check if the link itself is an archive link hosting all episodes
                    for (srv in links) {
                        if (srv.url.contains("store.animetoonhindi.com/archives/")) {
                            try {
                                val archiveDoc = app.get(srv.url, headers = mapOf("User-Agent" to USER_AGENT)).document
                                val subLinks = archiveDoc.select(".entry-content p a[href*='codedew.com'], h3 a[href*='codedew.com']")
                                for (subLink in subLinks) {
                                    val subHref = subLink.attr("href")
                                    val subName = subLink.text().trim()
                                    val subEpNumMatch = Regex("""\b(?:S\d+)?E(\d+)\b""", RegexOption.IGNORE_CASE).find(subName)
                                    val subEpNum = subEpNumMatch?.groupValues?.get(1)?.toIntOrNull()

                                    episodeList.add(
                                        newEpisode(toJson(EpisodeData(listOf(ServerLink(srv.name, subHref, langTag))))) {
                                            this.name = subName
                                            this.episode = subEpNum ?: (episodeList.size + 1)
                                        }
                                    )
                                }
                            } catch (e: Exception) {
                                currentServers.add(srv.copy(lang = langTag))
                            }
                        } else {
                            currentServers.add(srv.copy(lang = langTag))
                        }
                    }
                }
            }
            flushCurrentEpisode()
        }

        return if (isMovie && episodeList.size <= 1) {
            val movieServers = episodeList.firstOrNull()?.data ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, movieServers) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, episodeList)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serverList: List<ServerLink> = try {
            parseJson<EpisodeData>(data).servers
        } catch (e: Exception) {
            if (data.startsWith("http")) listOf(ServerLink("Server", data))
            else emptyList()
        }

        var loadedAny = false

        for (srv in serverList) {
            val serverUrl = srv.url.trim()
            val serverName = srv.name.trim()
            val langTag = if (!srv.lang.isNullOrBlank()) " [${srv.lang}]" else ""

            val resolvedUrl = if (serverUrl.contains("store.animetoonhindi.com")) {
                try {
                    val storeDoc = app.get(serverUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
                    storeDoc.select("a[href*='codedew.com']").firstOrNull()?.attr("href") ?: serverUrl
                } catch (e: Exception) {
                    serverUrl
                }
            } else {
                serverUrl
            }

            if (resolvedUrl.contains("codedew.com/zipper")) {
                if (resolveAndExtractZipper(resolvedUrl, serverName, langTag, subtitleCallback, callback)) {
                    loadedAny = true
                }
            } else if (resolvedUrl.contains("codedew.com/streambeta")) {
                if (extractStreamBeta(resolvedUrl, serverName, langTag, subtitleCallback, callback)) {
                    loadedAny = true
                }
            } else {
                try {
                    if (loadExtractor(resolvedUrl, subtitleCallback, callback)) {
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    // Ignore unsupported external link
                }
            }
        }

        return loadedAny
    }

    private suspend fun resolveAndExtractZipper(
        zipperUrl: String,
        serverName: String,
        langTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false
        try {
            val firstRes = app.get(
                zipperUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                followRedirects = false
            )

            val location = firstRes.headers["location"]
            if (!location.isNullOrBlank()) {
                val fullLoc = if (location.startsWith("/")) "https://codedew.com$location" else location
                if (fullLoc.contains("streambeta")) {
                    return extractStreamBeta(fullLoc, serverName, langTag, subtitleCallback, callback)
                } else if (fullLoc.contains("multiquality")) {
                    return extractMultiQuality(fullLoc, serverName, langTag, subtitleCallback, callback)
                } else {
                    return loadExtractor(fullLoc, subtitleCallback, callback)
                }
            }

            val doc1 = firstRes.document
            val dataHref1 = doc1.selectFirst("a[data-href]")?.attr("data-href")
            if (!dataHref1.isNullOrBlank()) {
                val target1 = fixCodedewUrl(dataHref1)
                val secondRes = app.get(
                    target1,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to zipperUrl),
                    cookies = firstRes.cookies,
                    followRedirects = false
                )

                val secondLoc = secondRes.headers["location"]
                if (!secondLoc.isNullOrBlank()) {
                    val fullLoc = if (secondLoc.startsWith("/")) "https://codedew.com$secondLoc" else secondLoc
                    return loadExtractor(fullLoc, subtitleCallback, callback)
                }

                val doc2 = secondRes.document
                val dataHref2 = doc2.selectFirst("a[data-href]")?.attr("data-href")
                if (!dataHref2.isNullOrBlank()) {
                    val finalTarget = fixCodedewUrl(dataHref2)
                    if (finalTarget.contains("streambeta")) {
                        return extractStreamBeta(finalTarget, serverName, langTag, subtitleCallback, callback)
                    } else if (finalTarget.contains("multiquality")) {
                        return extractMultiQuality(finalTarget, serverName, langTag, subtitleCallback, callback)
                    } else if (finalTarget.contains("pixeldra.in")) {
                        extractPixeldrain(finalTarget, "$serverName$langTag", callback)
                        return true
                    } else {
                        return loadExtractor(finalTarget, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return success
    }

    private suspend fun extractStreamBeta(
        streambetaUrl: String,
        serverName: String,
        langTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val html = app.get(streambetaUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
            val jsonRegex = Regex("""let playerSources\s*=\s*(\[[^;]+\]);""")
            val jsonMatch = jsonRegex.find(html)?.groupValues?.get(1) ?: return false
            val sources = parseJson<List<StreamBetaSource>>(jsonMatch)

            for (source in sources) {
                val subName = source.name ?: "Stream"
                val directUrl = source.url ?: source.streamUrl ?: continue

                if (directUrl.contains("pixeldra.in")) {
                    extractPixeldrain(directUrl, "$serverName $subName$langTag", callback)
                    found = true
                    continue
                }

                if (directUrl.contains(".workers.dev/")) {
                    try {
                        val b64 = directUrl.substringAfter(".workers.dev/").substringBefore("?")
                        val decodedJson = String(Base64.decode(b64, Base64.DEFAULT))
                        val innerUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (!innerUrl.isNullOrBlank()) {
                            if (innerUrl.contains("pixeldra.in")) {
                                extractPixeldrain(innerUrl, "$serverName $subName$langTag", callback)
                                found = true
                                continue
                            } else if (loadExtractor(innerUrl, subtitleCallback, callback)) {
                                found = true
                                continue
                            }
                        }
                    } catch (e: Exception) {
                        // ignore worker decode failure
                    }
                }

                val finalUrl = source.streamUrl ?: directUrl
                val isM3u8 = finalUrl.contains(".m3u8")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$serverName $subName$langTag",
                        url = finalUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://codedew.com/"
                        this.headers = mapOf(
                            "Referer" to "https://codedew.com/",
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = Qualities.P720.value
                    }
                )
                found = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }

    private suspend fun extractMultiQuality(
        multiqualityUrl: String,
        serverName: String,
        langTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(multiqualityUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
            val embedSrc = Regex("""<iframe[^>]*src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1) ?: return false

            loadExtractor(embedSrc, "https://codedew.com/", subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }

    private fun extractPixeldrain(
        url: String,
        displayName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = when {
            url.contains("/api/file/") -> url.substringAfter("/api/file/")
            url.contains("/u/") -> url.substringAfter("/u/")
            else -> null
        }?.substringBefore("?")?.substringBefore("/")

        if (!fileId.isNullOrBlank()) {
            val streamUrl = "https://pixeldra.in/api/file/$fileId"
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Pixeldrain ($displayName)",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    private fun fixCodedewUrl(url: String): String {
        val cleaned = url.replace("&amp;", "&")
        return if (cleaned.startsWith("/")) "https://codedew.com$cleaned" else cleaned
    }

    data class StreamBetaSource(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null
    )

    data class ServerLink(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("lang") val lang: String? = null
    )

    data class EpisodeData(
        @JsonProperty("servers") val servers: List<ServerLink>
    )
}
