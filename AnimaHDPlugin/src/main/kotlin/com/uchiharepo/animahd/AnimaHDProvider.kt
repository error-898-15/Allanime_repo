package com.uchiharepo.animahd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * AnimaHD Provider for CloudStream 3
 * Made by Jihad
 * Source: https://animahd.com
 */
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

        val items = document.select(".ff-card-wrap, article.post, .app-card-item, .category-card").mapNotNull { card ->
            card.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".ff-card-title, h2.entry-title, .entry-title, h2, h3")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val href = this.selectFirst("a[href]")?.attr("href")?.trim() ?: return null
        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/tag/")) return null

        val posterUrl = this.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty {
                    img.attr("src")
                }
            }
            if (src.startsWith("//")) "https:$src" else src
        }?.trim()

        val isMovie = href.contains("-movie-") || title.contains("Movie", ignoreCase = true)

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
        val q = query.trim()
        val cleanQuery = URLEncoder.encode(q, "UTF-8")

        // 1. Try WordPress REST API v2
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$cleanQuery&per_page=20&_embed=1"
            val res = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            )
            val posts = parseJson<List<WpPost>>(res.text)
            if (posts.isNotEmpty()) {
                val results = posts.mapNotNull { post ->
                    val rawTitle = post.title?.rendered
                        ?.replace(Regex("&amp;|&quot;|&#039;|&lt;|&gt;"), "")
                        ?.trim() ?: return@mapNotNull null
                    val link = post.link ?: return@mapNotNull null
                    val poster = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
                    val isMovie = rawTitle.contains("Movie", ignoreCase = true) || link.contains("-movie-")

                    if (isMovie) {
                        newMovieSearchResponse(rawTitle, link, TvType.AnimeMovie) {
                            this.posterUrl = poster
                        }
                    } else {
                        newTvSeriesSearchResponse(rawTitle, link, TvType.Anime) {
                            this.posterUrl = poster
                        }
                    }
                }
                if (results.isNotEmpty()) return results
            }
        } catch (e: Exception) {
            // Fall through to HTML search
        }

        // 2. Fallback to standard HTML search
        val searchUrl = "$mainUrl/?s=$cleanQuery"
        val document = app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        return document.select(".ff-card-wrap, article.post, .app-card-item, .category-card").mapNotNull { card ->
            card.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        val title = document.selectFirst("h1.ff-title, h1.entry-title, h1")?.text()?.trim()
            ?: "AnimaHD Anime"

        val poster = document.selectFirst(".ff-poster img, .post-thumbnail img, meta[property='og:image']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.attr("src")
        } ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val backdrop = document.selectFirst(".ff-hero-bg")?.attr("style")?.let { style ->
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }

        val plot = document.selectFirst(".ff-synopsis")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val metaText = document.selectFirst(".ff-meta")?.text() ?: ""
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(metaText)?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select(".ff-genres .ff-pill, .badge-box, .multilingual-badge")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = ArrayList<Episode>()
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

        val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 }
        )

        return if (isMovie && sortedEpisodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, sortedEpisodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
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
        var loadedAny = false
        val fileId = Regex("""(?:id=)([a-zA-Z0-9_-]+)""").find(data)?.groupValues?.get(1)
            ?: data.substringAfter("id=").substringBefore("&")

        if (fileId.isBlank()) return false

        val playerUrl = if (data.startsWith("http")) data else "$PLAYER_BASE/?id=$fileId"

        try {
            // 1. Visit player page to extract the High-Speed Worker CDN stream
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
                        source = this.name,
                        name = "${this.name} Fast CDN",
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

            // 2. Google Drive Direct Stream
            val driveDirectUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0"
            callback.invoke(
                newExtractorLink(
                    source = "Google Drive",
                    name = "Google Drive (Direct Stream)",
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
            } catch (e: Exception) {
                // Silently continue
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
