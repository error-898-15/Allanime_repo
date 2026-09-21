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
import org.jsoup.nodes.Element
import java.net.URLDecoder
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

        fun decodeBase64Safe(input: String): String {
            return try {
                val clean = input.replace("-", "+").replace("_", "/")
                val pad = clean.length % 4
                val padded = if (pad > 0) clean + "=".repeat(4 - pad) else clean
                val bytes = Base64.decode(padded, Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }

        fun decodeSecRoute(rawUrl: String): String {
            if (!rawUrl.contains("p=")) return rawUrl
            val clean = rawUrl.replace("&#038;", "&").replace("&amp;", "&")
            val pMatch = Regex("""[?&]p=([^&]+)""").find(clean)?.groupValues?.get(1) ?: return rawUrl
            return try {
                val unquoted = URLDecoder.decode(pMatch, "UTF-8")
                val decoded = decodeBase64Safe(unquoted)
                if (decoded.startsWith("http")) decoded else rawUrl
            } catch (e: Exception) {
                rawUrl
            }
        }
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

        val items = document.select("a.animahd-card, .animahd-card, .ff-card-wrap, article.post, article.article, .app-card-item, .category-card").mapNotNull { card ->
            card.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = when {
            this.tagName().equals("a", ignoreCase = true) -> this.attr("href")
            else -> this.selectFirst("a[href]")?.attr("href")
        }?.trim() ?: return null

        val cleanHref = decodeSecRoute(href)
        if (!cleanHref.startsWith("http") || cleanHref.contains("/category/") || cleanHref.contains("/tag/")) return null

        val title = this.selectFirst(".animahd-card-title, .ff-card-title, .article__title, h2.entry-title, .entry-title, h2, h3")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        // ১. ব্যাকগ্রাউন্ড স্টাইল থেকে পোস্টার সংগ্রহ
        var posterUrl = this.selectFirst(".animahd-poster, [style*='background-image']")?.attr("style")?.let { style ->
            Regex("""background-image:\s*url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }

        // ২. ফলব্যাক <img> ট্যাগ
        if (posterUrl.isNullOrBlank()) {
            posterUrl = this.selectFirst("img")?.let { img ->
                val src = img.attr("data-src").ifEmpty {
                    img.attr("data-lazy-src").ifEmpty {
                        img.attr("src")
                    }
                }
                decodeSecRoute(src)
            }
        }

        val isMovie = cleanHref.contains("-movie-") || title.contains("Movie", ignoreCase = true)

        return if (isMovie) {
            newMovieSearchResponse(title, cleanHref, TvType.AnimeMovie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, cleanHref, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val cleanQuery = URLEncoder.encode(q, "UTF-8")

        // ১. WordPress REST API v2
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
                        ?.replace(Regex("&amp;|&quot;|&#039;|&lt;|&gt;|&#8217;"), "")
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
            // HTML সার্চে ফলব্যাক
        }

        // ২. স্ট্যান্ডার্ড HTML সার্চ
        val searchUrl = "$mainUrl/?s=$cleanQuery"
        val document = app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        return document.select("a.animahd-card, .animahd-card, .ff-card-wrap, article.post, article.article, .app-card-item, .category-card").mapNotNull { card ->
            card.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = decodeSecRoute(url)
        val document = app.get(
            cleanUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        val title = document.selectFirst("h1.ff-title, h1.entry-title, h1")?.text()?.trim()
            ?: "AnimaHD Anime"

        var poster = document.selectFirst(".ff-poster img, .post-thumbnail img, meta[property='og:image']")?.let {
            if (it.tagName() == "meta") it.attr("content") else decodeSecRoute(it.attr("src"))
        } ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        if (poster.isNullOrBlank()) {
            poster = document.selectFirst(".animahd-poster, [style*='background-image']")?.attr("style")?.let { style ->
                Regex("""background-image:\s*url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
            }
        }

        val backdrop = document.selectFirst(".ff-hero-bg")?.attr("style")?.let { style ->
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }

        val plot = document.selectFirst(".ff-synopsis")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val metaText = document.selectFirst(".ff-meta, .animahd-card-year")?.text() ?: ""
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(metaText)?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select(".ff-genres .ff-pill, .badge-box, .multilingual-badge, .animahd-genre-box")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = ArrayList<Episode>()
        val epRows = document.select("a.app-ep-row-item, .app-ep-row-item, a[data-fileid]")

        epRows.forEachIndexed { index, row ->
            val seasonStr = row.attr("data-season").ifEmpty {
                row.selectFirst("[data-season]")?.attr("data-season") ?: ""
            }
            val seasonNum = Regex("""\b(\d+)\b""").find(seasonStr)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val rawEpTitle = row.selectFirst(".gdrive-ep-meta div:first-child")?.text()?.trim()
                ?: row.attr("title").trim().ifEmpty { row.text().trim() }
            val cleanEpTitle = rawEpTitle
                .replace(Regex("""^\[ANIMAHD\.COM\]\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
            val epNum = Regex("""(?i)(?:E|Episode\s*)(\d+)""").find(cleanEpTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            var fileId = row.selectFirst("[data-fileid]")?.attr("data-fileid")
                ?: row.attr("data-fileid")

            if (fileId.isNullOrBlank()) {
                val rawHref = row.attr("href")
                val decHref = decodeSecRoute(rawHref)
                fileId = Regex("""(?:file_id=|id=)([a-zA-Z0-9_-]+)""").find(decHref)?.groupValues?.get(1) ?: ""
            }

            if (fileId.isNotBlank()) {
                val epThumb = row.selectFirst("img")?.attr("src")?.let { decodeSecRoute(it) }
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

        val isMovie = title.contains("Movie", ignoreCase = true) || cleanUrl.contains("-movie-")

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
            newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, sortedEpisodes.firstOrNull()?.data ?: cleanUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, cleanUrl, TvType.Anime, sortedEpisodes) {
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
        val fileId = Regex("""(?:id=|file_id=)([a-zA-Z0-9_-]+)""").find(data)?.groupValues?.get(1)
            ?: data.substringAfter("id=").substringBefore("&")

        if (fileId.isBlank()) return false

        val playerUrl = "$PLAYER_BASE/?id=$fileId"

        try {
            // ১. High-Speed Worker CDN Stream
            val playerRes = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            )
            val doc = playerRes.document
            val streamUrl = doc.selectFirst("video source[src], source[src]")?.attr("src")?.trim()

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
        } catch (e: Exception) {
            // Continue
        }

        // ২. Google Drive Direct Stream
        try {
            val driveDirectUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            callback.invoke(
                newExtractorLink(
                    source = "Google Drive",
                    name = "Google Drive (Fast Stream)",
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
        } catch (e: Exception) {
            // Continue
        }

        // ৩. Google Drive preview iframe extractor
        try {
            val drivePreviewUrl = "https://drive.google.com/file/d/$fileId/preview"
            if (loadExtractor(drivePreviewUrl, "$mainUrl/", subtitleCallback, callback)) {
                loadedAny = true
            }
        } catch (e: Exception) {
            // Continue
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
