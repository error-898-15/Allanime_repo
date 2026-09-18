package com.uchiharepo.cinevood

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class CineVoodProvider : MainAPI() {
    override var mainUrl = "https://cinevood.rocks"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val MIRROR_DOMAINS = listOf(
            "https://cinevood.rocks",
            "https://cinevood.bingo",
            "https://cinevood.cv",
            "https://new1.cinevood.cv",
            "https://1cinevood.eu",
            "https://cinevood.net"
        )
    }

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        return try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            val currentDomain = try { URI(url).host } catch (e2: Exception) { null }
            var lastErr: Exception = e
            for (mirror in MIRROR_DOMAINS) {
                val mirrorHost = try { URI(mirror).host } catch (e2: Exception) { null }
                if (currentDomain != null && mirrorHost != null && mirrorHost.equals(currentDomain, ignoreCase = true)) continue
                val fallbackUrl = if (currentDomain != null) url.replace("https://$currentDomain", mirror) else mirror
                try {
                    return app.get(fallbackUrl, headers = headers).document
                } catch (err: Exception) {
                    lastErr = err
                }
            }
            throw lastErr
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/bollywood/" to "Bollywood Movies",
        "$mainUrl/hollywood/" to "Hollywood Movies",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/" to "Hollywood Hindi Dubbed",
        "$mainUrl/hindi-dubbed/south-dubbed/" to "South Hindi Dubbed",
        "$mainUrl/punjabi/" to "Punjabi Movies",
        "$mainUrl/web-series/" to "All Web Series",
        "$mainUrl/tv-shows/" to "TV Shows",
        "api:netflix" to "Netflix Web Series",
        "api:amazon" to "Amazon Prime",
        "api:hotstar" to "Disney+ Hotstar",
        "api:bengali" to "Bengali Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = if (request.data.startsWith("api:")) {
            val query = request.data.substringAfter("api:")
            searchViaApi(query, page)
        } else {
            val baseUrl = if (request.data.endsWith("/")) request.data else "${request.data}/"
            val url = if (page <= 1) baseUrl else "${baseUrl}page/$page/"
            try {
                val document = getDocument(url)
                val results = document.select("article.latestPost, article.post, article.excerpt, article").mapNotNull {
                    it.toSearchResult()
                }
                if (results.isEmpty()) {
                    val fallbackKeyword = request.name.lowercase()
                        .replace("movies", "")
                        .replace("web series", "")
                        .replace("all", "")
                        .trim()
                    if (fallbackKeyword.isNotBlank()) searchViaApi(fallbackKeyword, page) else emptyList()
                } else results
            } catch (e: Exception) {
                val fallbackKeyword = request.name.lowercase()
                    .replace("movies", "")
                    .replace("web series", "")
                    .replace("all", "")
                    .trim()
                if (fallbackKeyword.isNotBlank()) searchViaApi(fallbackKeyword, page) else emptyList()
            }
        }
        return newHomePageResponse(request.name, items)
    }

    private fun cleanImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim()
        if (clean.startsWith("data:image") || clean.contains("cvlogo.png") || clean.contains("favicon")) {
            return null
        }
        if (clean.startsWith("//")) {
            clean = "https:$clean"
        } else if (clean.startsWith("/")) {
            clean = "$mainUrl$clean"
        }
        if (clean.contains("image.tmdb.org")) {
            clean = clean.replace("/w185/", "/w500/")
                .replace("/w342/", "/w500/")
                .replace("/w300/", "/w500/")
                .replace("/w780/", "/w500/")
        }
        return clean
    }

    private fun extractPoster(element: Element?): String? {
        if (element == null) return null
        val img = if (element.tagName().equals("img", ignoreCase = true)) element else element.selectFirst("img")
        val raw = if (img != null) {
            val dSrc = img.attr("data-src").trim()
            val dLazy = img.attr("data-lazy-src").trim()
            val dOrig = img.attr("data-original").trim()
            val dSrcset = img.attr("data-srcset").substringBefore(" ").trim()
            val srcset = img.attr("srcset").substringBefore(" ").trim()
            val src = img.attr("src").trim()
            when {
                dSrc.isNotBlank() && !dSrc.startsWith("data:image") -> dSrc
                dLazy.isNotBlank() && !dLazy.startsWith("data:image") -> dLazy
                dOrig.isNotBlank() && !dOrig.startsWith("data:image") -> dOrig
                dSrcset.isNotBlank() && !dSrcset.startsWith("data:image") -> dSrcset
                srcset.isNotBlank() && !srcset.startsWith("data:image") -> srcset
                src.isNotBlank() && !src.startsWith("data:image") -> src
                else -> null
            }
        } else {
            val directSrc = element.attr("src").ifEmpty { element.attr("data-src") }.trim()
            if (directSrc.isNotBlank() && !directSrc.startsWith("data:image")) directSrc else null
        }
        return cleanImageUrl(raw)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h2.title a, .front-view-title a, h2 a, h3 a, h2, h3")
        val title = titleEl?.text()?.trim()?.ifEmpty { null }
            ?: titleEl?.attr("title")?.trim()?.ifEmpty { null }
            ?: this.selectFirst("a.post-image")?.attr("title")?.trim()?.ifEmpty { null }
            ?: this.selectFirst("img")?.attr("title")?.trim()?.ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.ifEmpty { null }
            ?: return null

        val href = this.selectFirst("h2.title a, .front-view-title a, h2 a, a.post-image")?.attr("href")
            ?: this.selectFirst("a[href]")?.attr("href")
            ?: return null

        if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) {
            return null
        }

        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        val posterUrl = extractPoster(this)
        val isSeries = fullUrl.contains("/web-series/") || fullUrl.contains("/tv-shows/") || title.contains("Season", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    data class CineVoodSearchHit(
        @JsonProperty("document") val document: CineVoodSearchDoc? = null
    )

    data class CineVoodSearchDoc(
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("permalink") val permalink: String? = null,
        @JsonProperty("post_thumbnail") val postThumbnail: String? = null
    )

    data class CineVoodSearchResponse(
        @JsonProperty("found") val found: Int? = null,
        @JsonProperty("hits") val hits: List<CineVoodSearchHit>? = null
    )

    private suspend fun searchViaApi(query: String, page: Int = 1): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            val apiRes = app.get(
                "$mainUrl/search.php?q=$encoded&page=$page",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/search.html",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).parsedSafe<CineVoodSearchResponse>()

            apiRes?.hits?.mapNotNull { hit ->
                val doc = hit.document ?: return@mapNotNull null
                val title = doc.postTitle?.trim() ?: return@mapNotNull null
                val permalink = doc.permalink?.trim() ?: return@mapNotNull null
                val fullUrl = if (permalink.startsWith("http")) permalink else "$mainUrl$permalink"
                val posterUrl = cleanImageUrl(doc.postThumbnail)
                val isSeries = fullUrl.contains("/web-series/") || fullUrl.contains("/tv-shows/") || title.contains("Season", ignoreCase = true)

                if (isSeries) {
                    newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = searchViaApi(query, 1)
        if (results.isNotEmpty()) {
            return results
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            val document = getDocument("$mainUrl/?s=$encoded")
            document.select("article.latestPost, article.post, article.excerpt, div.result-item, article").mapNotNull {
                it.toSearchResult()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.selectFirst("h1.single-title, h1.entry-title, h1")?.text()?.trim()
            ?: "Unknown Title"

        val posterUrl = cleanImageUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: extractPoster(document.selectFirst(".featured-thumbnail, .post-single-content, .entry-content, .thecontent"))
        )

        val plot = document.selectFirst("div.post-single-content p, div.entry-content p, .thecontent p")?.text()?.trim()
        val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(title)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("div.thecategory a, div.thetags a, a[rel='category tag'], div.tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Movies", ignoreCase = true) }
            .distinct()

        val isSeries = url.contains("/web-series/") || url.contains("/tv-shows/") || title.contains("Season", ignoreCase = true)

        val episodeLinks = document.select(".thecontent a, div.post-single-content a, div.entry-content a, a.maxbutton")
            .filter { a ->
                val t = a.text().trim()
                val href = a.attr("href").trim()
                href.isNotBlank() && (
                    t.contains("Episode", ignoreCase = true) ||
                    t.contains("EP ", ignoreCase = true) ||
                    t.contains("EP-", ignoreCase = true) ||
                    Regex("""EP\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                    Regex("""E\d+""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                )
            }

        if (isSeries && episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexedNotNull { index, ep ->
                val epHref = ep.attr("href").trim()
                if (epHref.isBlank() || epHref.startsWith("#")) return@mapIndexedNotNull null
                val epText = ep.text().trim()
                val sMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                val eMatch = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                val season = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                newEpisode(epHref) {
                    this.name = epText
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = posterUrl
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
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
        val document = getDocument(data)
        var foundAny = false

        val directAnchors = document.select("a.maxbutton, a[href*='hubcloud'], a[href*='gdflix'], a[href*='pixeldrain'], a[href*='fastdl'], a[href*='vifix'], a.btn, div.thecontent a")

        for (a in directAnchors) {
            val href = a.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("javascript:") || href.contains("/category/") || href.contains("/tag/")) continue

            val text = a.text().trim()
            val quality = when {
                text.contains("2160p", true) || text.contains("4K", true) -> Qualities.P2160.value
                text.contains("1080p", true) -> Qualities.P1080.value
                text.contains("720p", true) -> Qualities.P720.value
                text.contains("480p", true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            try {
                if (href.contains("hubcloud") || href.contains("gdflix") || href.contains("pixeldrain") || href.contains("fastdl") || href.contains("streamtape") || href.contains("dood")) {
                    val loaded = loadExtractor(href, subtitleCallback, callback)
                    if (loaded) foundAny = true
                } else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
                    callback(
                        newExtractorLink(
                            name = if (text.isNotBlank()) text else "CineVood Direct",
                            source = name,
                            url = href,
                            type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                        }
                    )
                    foundAny = true
                }
            } catch (_: Exception) { }
        }

        return foundAny
    }
}
