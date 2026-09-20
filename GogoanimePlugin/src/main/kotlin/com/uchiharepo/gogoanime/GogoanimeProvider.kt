package com.uchiharepo.gogoanime

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

class GogoanimeProvider : MainAPI() {
    override var mainUrl = "https://gogoanime.by"
    override var name = "Gogoanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Updates",
        "$mainUrl/series/?status=&type=TV+Show&order=update&page=" to "Anime TV Series",
        "$mainUrl/series/?status=&type=Movie&order=update&page=" to "Anime Movies",
        "$mainUrl/series/?status=&type=ONA&order=update&page=" to "ONA Series",
        "$mainUrl/series/?status=&type=OVA&order=update&page=" to "OVA Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.endsWith("/page/")) {
            "${request.data}$page/"
        } else {
            "${request.data}$page"
        }
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        val items = document.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("div.bsx a") ?: return null
        val title = link.attr("title").ifBlank { link.selectFirst("div.tt")?.ownText() } ?: return null
        val href = fixUrl(link.attr("href"))
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val epText = this.selectFirst("span.epx")?.text()?.trim()
        val typeText = this.selectFirst("div.typez")?.text()?.trim().orEmpty()
        val isMovie = typeText.contains("Movie", ignoreCase = true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addSub(epText?.replace(Regex("[^0-9]"), "")?.toIntOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        return document.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        var currentUrl = fixUrl(url)
        var document = app.get(
            currentUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        // If an episode URL was loaded directly, resolve parent series URL
        if (!currentUrl.contains("/series/")) {
            val allEpisodesHref = document.selectFirst("a[aria-label='All Episodes']")?.attr("href")
                ?: document.selectFirst("a[href*='/series/']")?.attr("href")
            if (!allEpisodesHref.isNullOrBlank()) {
                currentUrl = fixUrl(allEpisodesHref)
                document = app.get(
                    currentUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                ).document
            }
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.thumb img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val plot = document.selectFirst("div.ninfo p")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()

        val tags = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }
        val statusText = document.selectFirst("div.spe span:contains(Status)")?.ownText()?.trim()
            ?: document.selectFirst("div.spe span:contains(Status)")?.text()?.substringAfter("Status:")?.trim()
        val typeText = document.selectFirst("div.spe span:contains(Type)")?.ownText()?.trim()
            ?: document.selectFirst("div.spe span:contains(Type)")?.text()?.substringAfter("Type:")?.trim()
        val isMovie = typeText?.contains("Movie", ignoreCase = true) == true
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val episodes = document.select("div.episodes-container div.episode-item").mapNotNull { epItem ->
            val a = epItem.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(a.attr("href"))
            val epNumStr = epItem.attr("data-episode-number").ifBlank {
                Regex("""(\d+)""").find(a.text())?.groupValues?.get(1)
            }
            val epNum = epNumStr?.toIntOrNull()
            newEpisode(epHref) {
                this.name = a.text().trim()
                this.episode = epNum
            }
        }.reversed()

        return if (isMovie && episodes.isEmpty()) {
            newMovieLoadResponse(title, currentUrl, TvType.AnimeMovie, currentUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, currentUrl, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epDocument = app.get(
            data,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        ).document

        var loadedAny = false

        // Extract original servers provided by the episode
        val serverElements = epDocument.select("li.player-type-link[data-src]")
        for (serverEl in serverElements) {
            val serverName = serverEl.text().trim().ifBlank { "Original" }
            val rawPlayerUrl = serverEl.attr("data-src").trim()
            if (rawPlayerUrl.isBlank()) continue
            val playerUrl = fixUrl(rawPlayerUrl)

            try {
                val playerDoc = app.get(
                    playerUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to data
                    )
                ).document

                // 1. Check for player iframe embed (Megavid or MegaPlay)
                val iframeSrc = playerDoc.selectFirst("iframe[src]")?.attr("src")?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    val cleanIframe = fixUrl(iframeSrc)

                    if (cleanIframe.contains("megavid.buzz")) {
                        try {
                            val megavidHtml = app.get(
                                cleanIframe,
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                            ).text

                            val payloadMatch = Regex("""<script[^>]+id="player-payload"[^>]*>([\s\S]*?)</script>""").find(megavidHtml)
                            val sourceApi = if (payloadMatch != null) {
                                parseJson<MegavidPayload>(payloadMatch.groupValues[1]).sourceUrl
                            } else {
                                cleanIframe.substringAfter("megavid.buzz").substringBefore("?") + "/source"
                            }

                            if (!sourceApi.isNullOrBlank()) {
                                val fullApiUrl = if (sourceApi.startsWith("http")) sourceApi else "https://megavid.buzz$sourceApi"
                                val srcData = app.get(
                                    fullApiUrl,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to cleanIframe
                                    )
                                ).parsedSafe<MegavidSourceResponse>()

                                val masterHls = srcData?.source
                                if (!masterHls.isNullOrBlank()) {
                                    val providerTag = srcData.provider?.let { " ($it)" } ?: ""
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "Gogoanime $serverName$providerTag",
                                            url = masterHls,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://megavid.buzz/"
                                            this.headers = mapOf(
                                                "Referer" to "https://megavid.buzz/",
                                                "User-Agent" to USER_AGENT
                                            )
                                        }
                                    )
                                    loadedAny = true

                                    // Multi-quality sub-streams
                                    try {
                                        M3u8Helper.generateM3u8(
                                            source = this.name,
                                            streamUrl = masterHls,
                                            referer = "https://megavid.buzz/",
                                            headers = mapOf(
                                                "Referer" to "https://megavid.buzz/",
                                                "User-Agent" to USER_AGENT
                                            ),
                                            name = "Gogoanime $serverName"
                                        ).forEach { subLink ->
                                            callback.invoke(subLink)
                                        }
                                    } catch (e: Exception) {
                                        // Ignore sub-stream parsing errors
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Continue to next server
                        }
                    } else if (cleanIframe.contains("megaplay.su")) {
                        try {
                            val mpHtml = app.get(
                                cleanIframe,
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                            ).text
                            val m3u8Match = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(mpHtml)
                            if (m3u8Match != null) {
                                val m3u8Url = m3u8Match.groupValues[1]
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "Gogoanime $serverName (MegaPlay HLS)",
                                        url = m3u8Url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://megaplay.su/"
                                        this.headers = mapOf(
                                            "Referer" to "https://megaplay.su/",
                                            "User-Agent" to USER_AGENT
                                        )
                                    }
                                )
                                loadedAny = true
                            }
                        } catch (e: Exception) {
                            // Continue
                        }
                    } else {
                        // Standard external extractors if present
                        try {
                            if (loadExtractor(cleanIframe, data, subtitleCallback, callback)) {
                                loadedAny = true
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }

                // 2. Check for Blogger / Direct MP4
                val playerHtml = playerDoc.html()
                val fileMatch = Regex("""var fileUrl\s*=\s*["']([^"']+)["']""").find(playerHtml)
                if (fileMatch != null) {
                    val directUrl = fileMatch.groupValues[1]
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "Gogoanime $serverName (Direct MP4)",
                            url = directUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                    loadedAny = true
                }
            } catch (e: Exception) {
                // Continue
            }
        }

        return loadedAny
    }

    data class MegavidPayload(
        @JsonProperty("sourceUrl") val sourceUrl: String? = null
    )

    data class MegavidSourceResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("provider") val provider: String? = null
    )
}
