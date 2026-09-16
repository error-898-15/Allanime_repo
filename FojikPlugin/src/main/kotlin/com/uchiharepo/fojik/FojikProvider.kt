package com.uchiharepo.fojik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FojikProvider : MainAPI() {
    override var mainUrl = "https://fojik.site"
    override var name = "Fojik"
    override val hasMainPage = true
    override var lang = "bn"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movie/page/" to "Latest Movies",
        "$mainUrl/genre/tv-web-series/page/" to "TV & Web Series",
        "$mainUrl/genre/dual-audio/page/" to "Dual Audio Collection",
        "$mainUrl/genre/4k-ultra-hd-2160p/page/" to "4K Ultra HD Movies",
        "$mainUrl/genre/bollywood-hindi/page/" to "Bollywood Hindi",
        "$mainUrl/genre/hollywood-english/page/" to "Hollywood English"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data.replace("/page/", "/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(url, headers = defaultHeaders).document
        val homeItems = document.select("article.item, div.items article").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("div.data h3 a, h3 a, div.title a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrlNull(titleElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("div.poster img")?.let { img ->
                img.attr("data-src").ifEmpty {
                    img.attr("src")
                }
            }
        )

        val quality = this.selectFirst("span.quality, div.mepo span.quality")?.text()?.trim()
        val isTvSeries = this.hasClass("tvshows") || href.contains("/tvshows/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(quality ?: "")
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality ?: "")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = defaultHeaders).document

        return document.select("article.item, div.result-item article, div.search-page article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("div.data h1, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown Title"

        val posterUrl = fixUrlNull(
            document.selectFirst("div.poster img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = document.selectFirst("div#info div.wp-content p, div.wp-content p")?.text()?.trim()

        val year = document.selectFirst("span.date, span.country, div.extra span")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val rating = document.selectFirst("div.rating span, span.dt_rating_vgs")?.text()?.trim()
        val tags = document.select("div.sgeneros a, div.genres a").map { it.text().trim() }
        val isTvSeries = url.contains("/tvshows/") || document.selectFirst("div#seasons, ul.episodios") != null

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("div.se-c, div#seasons div.se-a")

            seasonElements.forEachIndexed { seasonIdx, seasonEl ->
                val seasonNum = seasonEl.selectFirst("span.se-t")?.text()?.toIntOrNull() ?: (seasonIdx + 1)
                val episodeElements = seasonEl.select("ul.episodios li, div.episodiotitle")

                episodeElements.forEachIndexed { epIdx, epEl ->
                    val epHref = fixUrlNull(epEl.selectFirst("a")?.attr("href")) ?: return@forEachIndexed
                    val epTitle = epEl.selectFirst("div.episodiotitle a, a")?.text()?.trim() ?: "Episode ${epIdx + 1}"
                    val epNum = epEl.selectFirst("div.numerando")?.text()
                        ?.let { Regex("-\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                        ?: (epIdx + 1)

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.toDoubleOrNull()
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = rating?.toDoubleOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = defaultHeaders).document

        // Use ONLY original servers provided by the target website from links_table
        val linkRows = document.select("div.links_table table tbody tr, table.fix-table tbody tr")

        if (linkRows.isEmpty()) {
            val playerOptions = document.select("ul#playeroptionsul li[data-post]")
            for (option in playerOptions) {
                val embedUrl = option.attr("data-url")
                if (embedUrl.isNotEmpty()) {
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            }
            return true
        }

        for (row in linkRows) {
            val qualityText = row.selectFirst("strong.quality, td:nth-child(2)")?.text()?.trim() ?: "Default"
            val langText = row.selectFirst("td:nth-child(3)")?.text()?.trim() ?: ""
            val sizeText = row.selectFirst("td:nth-child(4)")?.text()?.trim() ?: ""

            val originalServerName = buildString {
                append("Fojik ")
                append(qualityText)
                if (langText.isNotEmpty() && !langText.contains("--")) {
                    append(" ($langText)")
                }
                if (sizeText.isNotEmpty() && !sizeText.contains("--")) {
                    append(" [$sizeText]")
                }
            }

            val form = row.selectFirst("form") ?: continue
            val actionUrl = fixUrlNull(form.attr("action")) ?: continue
            val fu = form.selectFirst("input[name=FU]")?.attr("value") ?: ""
            val fn = form.selectFirst("input[name=FN]")?.attr("value") ?: ""

            try {
                val postResponse = app.post(
                    actionUrl,
                    headers = mapOf(
                        "Referer" to data,
                        "User-Agent" to defaultHeaders["User-Agent"]!!,
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = mapOf(
                        "FU" to fu,
                        "FN" to fn
                    )
                )

                val redirectedUrl = postResponse.url
                val respBody = postResponse.text

                val directStreamRegex = Regex("""href=["'](https?://[^"']*(?:drive\.google|hubcloud|fastdl|gdflix|mediafire)[^"']*)["']""")
                val foundLink = directStreamRegex.find(respBody)?.groupValues?.get(1) ?: redirectedUrl

                if (foundLink.isNotEmpty() && foundLink != actionUrl) {
                    loadExtractor(foundLink, subtitleCallback, callback)
                } else {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = originalServerName,
                            url = redirectedUrl.ifEmpty { actionUrl },
                            referer = data,
                            quality = getQualityFromName(qualityText)
                        )
                    )
                }
            } catch (e: Exception) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = originalServerName,
                        url = actionUrl,
                        referer = data,
                        quality = getQualityFromName(qualityText)
                    )
                )
            }
        }

        return true
    }

    private fun getQualityFromName(quality: String): Int {
        return when {
            quality.contains("4K", ignoreCase = true) || quality.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            quality.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            quality.contains("720", ignoreCase = true) -> Qualities.P720.value
            quality.contains("480", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}
