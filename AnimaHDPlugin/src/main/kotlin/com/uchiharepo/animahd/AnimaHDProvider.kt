package com.uchiharepo.animahd

import android.util.Base64
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
import java.net.URLEncoder
import java.nio.charset.Charset

class AnimaHDProvider : MainAPI() {
    override var mainUrl = "https://animahd.com"
    override var name = "AnimaHD"
    override val hasMainPage = true
    override var lang = "hi"
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
        const val PLAYER_BASE = "https://youranimewatchingplace.animahd.fun"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/ongoing/page/" to "Ongoing Anime",
        "$mainUrl/category/hindi-dub/page/" to "Hindi Dub",
        "$mainUrl/category/english-dub/page/" to "English Dub",
        "$mainUrl/category/anime-movies/page/" to "Anime Movies",
        "$mainUrl/category/action/page/" to "Action Anime",
        "$mainUrl/category/adventure/page/" to "Adventure Anime",
        "$mainUrl/category/fantasy/page/" to "Fantasy Anime",
        "$mainUrl/category/comedy/page/" to "Comedy Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        val items = document.select("a.animahd-card, .animahd-card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun cleanUrl(rawHref: String): String {
        if (rawHref.contains("p=")) {
            try {
                val encodedPart = rawHref.substringAfter("p=").substringBefore("&")
                val decoded = String(Base64.decode(encodedPart, Base64.DEFAULT), Charset.forName("UTF-8"))
                if (decoded.startsWith("http")) return decoded
            } catch (_: Exception) {
            }
        }
        return fixUrl(rawHref)
    }

    private fun extractPoster(element: Element): String? {
        val img = element.selectFirst("img")
        val imgSrc = img?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("data-lazy-src") }
        }?.trim()

        if (!imgSrc.isNullOrBlank() && !imgSrc.startsWith("data:image") && !imgSrc.contains("\${imgUrl}")) {
            return if (imgSrc.startsWith("//")) "https:$imgSrc" else imgSrc
        }

        val style = element.selectFirst(".animahd-poster, [style*='background-image']")?.attr("style") ?: ""
        val match = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
        if (match != null) {
            val url = match.groupValues[1].trim()
            if (url.isNotBlank() && !url.contains("\${imgUrl}")) {
                return if (url.startsWith("//")) "https:$url" else url
            }
        }
        return null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") } ?: return null
        val fullUrl = cleanUrl(rawHref)
        if (!fullUrl.startsWith("http") || fullUrl.contains("/category/") || fullUrl.contains("/tag/")) {
            return null
        }

        val title = this.selectFirst(".animahd-card-title")?.text()?.trim()
            ?: this.attr("title").trim().ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.ifEmpty { null }
            ?: return null

        val posterUrl = extractPoster(this)
        val isMovie = title.contains("Movie", ignoreCase = true) || fullUrl.contains("-movie-")

        return if (isMovie) {
            newMovieSearchResponse(title, fullUrl, TvType.AnimeMovie) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, fullUrl, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // 1. Try WP-JSON REST API for fast structured results
        try {
            val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=${URLEncoder.encode(query, "UTF-8")}&_embed&per_page=20"
            val response = app.get(
                wpApiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            )
            val posts = parseJson<List<WpPost>>(response.text)
            for (post in posts) {
                val title = post.title?.rendered?.replace("&#8217;", "'")?.replace("&amp;", "&")?.trim() ?: continue
                val link = post.link ?: continue
                val poster = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
                val isMovie = title.contains("Movie", ignoreCase = true) || link.contains("-movie-")

                if (isMovie) {
                    results.add(
                        newMovieSearchResponse(title, link, TvType.AnimeMovie) {
                            this.posterUrl = poster
                        }
                    )
                } else {
                    results.add(
                        newAnimeSearchResponse(title, link, TvType.Anime) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } catch (_: Exception) {
        }

        if (results.isNotEmpty()) {
            return results.distinctBy { it.url }
        }

        // 2. Fallback to HTML Search
        try {
            val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
            val document = app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).document

            document.select("a.animahd-card, article.post, .search-result").forEach { element ->
                element.toSearchResult()?.let { results.add(it) }
            }
        } catch (_: Exception) {
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        val title = document.selectFirst("h1.ff-title, .ff-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "AnimaHD Media"

        val poster = document.selectFirst(".ff-poster-wrap img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val backdrop = document.selectFirst(".ff-hero-bg")?.attr("style")?.let { style ->
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }

        val plot = document.selectFirst(".ff-synopsis")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val metaText = document.selectFirst(".ff-meta")?.text() ?: ""
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(metaText)?.groupValues?.get(1)?.toIntOrNull()
        val rating = Regex("""(\d+\.\d+)""").find(metaText)?.groupValues?.get(1)?.toRatingInt()

        val tags = document.select(".ff-genres .ff-pill, .badge-box, .multilingual-badge")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = mutableListOf<Episode>()
        val epRows = document.select("a.app-ep-row-item, a[data-fileid]")

        epRows.forEachIndexed { index, row ->
            val seasonStr = row.attr("data-season")
            val seasonNum = Regex("""\b(\d+)\b""").find(seasonStr)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val rawEpTitle = row.selectFirst(".gdrive-ep-meta div:first-child")?.text()?.trim()
                ?: row.attr("title").trim().ifEmpty { row.text().trim() }

            val cleanEpTitle = rawEpTitle
                .replace(Regex("""^\[ANIMAHD\.COM\]\s*""", RegexOption.IGNORE_CASE), "")
                .trim()

            val epNum = Regex("""(?i)(?:E|Episode\s*)(\d+)""").find(cleanEpTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            val fileId = row.attr("data-fileid").ifEmpty {
                val href = row.attr("href")
                Regex("""file_id=([a-zA-Z0-9_-]+)""").find(href)?.groupValues?.get(1) ?: ""
            }

            if (fileId.isNotBlank()) {
                val epThumb = row.selectFirst("img")?.attr("src")
                val streamPlayerUrl = "$PLAYER_BASE/?id=$fileId"

                episodes.add(
                    newEpisode(streamPlayerUrl) {
                        this.name = if (cleanEpTitle.isNotBlank()) cleanEpTitle else "Episode $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = epThumb ?: poster
                    }
                )
            }
        }

        val isMovie = title.contains("Movie", ignoreCase = true) || url.contains("-movie-")

        if (episodes.isEmpty()) {
            val singleFileId = document.selectFirst("[data-fileid]")?.attr("data-fileid")
                ?: Regex("""(?:file_id=|id=)([a-zA-Z0-9_-]{20,})""").find(document.html())?.groupValues?.get(1)

            if (!singleFileId.isNullOrBlank()) {
                episodes.add(
                    newEpisode("$PLAYER_BASE/?id=$singleFileId") {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }
        }

        return if (isMovie && episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.rating = rating
                this.tags = tags
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.rating = rating
                this.tags = tags
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var loadedAny = false

        val fileId = Regex("""(?:id=)([a-zA-Z0-9_-]+)""").find(data)?.groupValues?.get(1)
            ?: data.substringAfter("id=").substringBefore("&")

        if (fileId.isBlank()) return false

        val playerUrl = if (data.startsWith("http")) data else "$PLAYER_BASE/?id=$fileId"

        try {
            // 1. Resolve Primary High-Speed Worker CDN Stream
            val playerRes = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            )

            val doc = playerRes.document
            val sourceTag = doc.selectFirst("video source[src], source[src]")
            val streamUrl = sourceTag?.attr("src")?.trim()

            if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "AnimaHD Fast CDN",
                        name = "AnimaHD Fast CDN (High-Speed Stream)",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playerRes.url
                        this.headers = mapOf(
                            "Referer" to playerRes.url,
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                loadedAny = true
            }

            // 2. Google Drive Alternate Direct Stream (Force Download)
            val driveDirectUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0"
            callback.invoke(
                newExtractorLink(
                    source = "Google Drive",
                    name = "Google Drive (Alternate Direct Stream)",
                    url = driveDirectUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://drive.google.com/"
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                    this.quality = Qualities.P1080.value
                }
            )
            loadedAny = true

            // 3. Google Drive preview iframe extractor
            try {
                val drivePreviewUrl = "https://drive.google.com/file/d/$fileId/preview"
                if (loadExtractor(drivePreviewUrl, playerRes.url, subtitleCallback, callback)) {
                    loadedAny = true
                }
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            val driveDirectUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0"
            callback.invoke(
                newExtractorLink(
                    source = "Google Drive",
                    name = "Google Drive (Fallback Stream)",
                    url = driveDirectUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://drive.google.com/"
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                    this.quality = Qualities.P1080.value
                }
            )
            loadedAny = true
        }

        return loadedAny
    }

    data class WpPost(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("title") val title: WpRendered? = null,
        @JsonProperty("_embedded") val embedded: WpEmbedded? = null
    )

    data class WpRendered(
        @JsonProperty("rendered") val rendered: String? = null
    )

    data class WpEmbedded(
        @JsonProperty("wp:featuredmedia") val featuredMedia: List<WpMediaItem>? = null
    )

    data class WpMediaItem(
        @JsonProperty("source_url") val sourceUrl: String? = null
    )
}
