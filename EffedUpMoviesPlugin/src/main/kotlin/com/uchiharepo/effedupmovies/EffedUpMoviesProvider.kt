package com.uchiharepo.effedupmovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class EffedUpMoviesProvider : MainAPI() {
    override var mainUrl = "https://www.effedupmovies.com"
    override var name = "EffedUpMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Movies",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/gore-gruesome-splatter/page/" to "Gore & Splatter",
        "$mainUrl/category/psychological-thrillers/page/" to "Psychological Thrillers",
        "$mainUrl/category/bizarre-surreal/page/" to "Bizarre & Surreal",
        "$mainUrl/category/cult/page/" to "Cult & Commune",
        "$mainUrl/category/serial-killers/page/" to "Serial Killers",
        "$mainUrl/category/movies-based-on-a-true-story/page/" to "True Story",
        "$mainUrl/category/revenge/page/" to "Revenge",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/category/abduction/page/" to "Captivity & Kidnapping",
        "$mainUrl/category/snuff-film/page/" to "Snuff",
        "$mainUrl/category/documentary/page/" to "Documentaries"
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
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document
        val home = document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
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
        if (raw.isBlank() || raw.startsWith("data:image")) return null
        val cleaned = if (raw.startsWith("//")) "https:$raw" else raw
        if (cleaned.contains("favicon", ignoreCase = true) ||
            cleaned.contains("gravatar.com", ignoreCase = true) ||
            cleaned.contains("avatar-", ignoreCase = true)
        ) {
            return null
        }
        return cleaned
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("h2.entry-title a, .entry-title a, a[rel=bookmark], a") ?: return null
        val href = linkEl.attr("href").trim()
        if (href.isBlank() || !href.startsWith("http") || href.contains("/category/") || href.contains("/tag/") || href.contains("/author/")) {
            return null
        }

        var title = this.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
            ?: linkEl.text().trim()

        if (title.isBlank() || title.contains("September", ignoreCase = true) || title.contains("August", ignoreCase = true) || title.length < 2) {
            val alt = this.selectFirst("img")?.attr("alt")?.trim()
            title = if (!alt.isNullOrBlank()) {
                alt
            } else {
                href.removeSuffix("/").substringAfterLast("/")
                    .split("-")
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
        }

        val posterUrl = extractImageUrl(this)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val document = app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document
        return document.select("article.post").mapNotNull {
            it.toSearchResult()
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

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: url.removeSuffix("/").substringAfterLast("/")
                .split("-")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val poster = extractImageUrl(document.selectFirst(".post-thumbnail img, figure img, .attachment-post-thumbnail, .entry-content img, article img"))
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("link[rel=image_src]")?.attr("href")?.takeIf { it.isNotBlank() }

        val plot = document.selectFirst(".entry-content p:matchesOwn((?i)(plot|synopsis))")?.let {
            it.text().replace(Regex("""(?i)^(?:Plot\s*(?:&#8211;|-)?\s*Spoilers?:|Synopsis:)\s*"""), "").trim()
        } ?: document.selectFirst(".entry-content p")?.text()?.trim()
          ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst(".entry-content:matchesOwn((?i)year:)")?.let {
                Regex("""(?i)Year:\s*(\d{4})""").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
            }
            ?: Regex("""-(\d{4})/?$""").find(url)?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("a[href*='/category/']").map { it.text().trim() }
            .filter { it.isNotBlank() && !it.startsWith("Effed Up", ignoreCase = true) && !it.equals("Admin", ignoreCase = true) }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).document

        var loadedAny = false
        val streamUrls = mutableListOf<String>()

        // 1. Direct <source src="..."> elements
        document.select("source[src]").forEach {
            val src = it.attr("src").trim()
            if (src.contains(".m3u8")) {
                streamUrls.add(src)
            }
        }

        // 2. Fallback: Parse m3u8 URLs from video script or direct links
        if (streamUrls.isEmpty()) {
            val html = document.html()
            val m3u8Regex = Regex("""https?://[a-zA-Z0-9.-]+\.effedupmovies\.com/hls/[^"'\s<>]+\.m3u8""")
            m3u8Regex.findAll(html).forEach { match ->
                streamUrls.add(match.value)
            }

            document.select("a[href*='.m3u8']").forEach {
                val href = it.attr("href").trim()
                if (href.startsWith("http")) {
                    streamUrls.add(href)
                }
            }
        }

        // 3. Subtitles: Parse <track src="...">
        document.select("track[src]").forEach { track ->
            val subSrc = track.attr("src").trim()
            if (subSrc.isNotBlank()) {
                val cleanSubUrl = if (subSrc.startsWith("//")) "https:$subSrc" else subSrc
                val label = track.attr("label").ifBlank {
                    track.attr("srclang").ifBlank { "English" }
                }
                try {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = cleanSubUrl
                        )
                    )
                } catch (e: Exception) {
                    // Ignore subtitle exceptions
                }
            }
        }

        // 4. Emit Original Stream Links (S1, S2, S3, etc.)
        for (rawUrl in streamUrls.distinct()) {
            val cleanStreamUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

            val serverName = when {
                cleanStreamUrl.contains("s1.vvvv.effedupmovies.com") -> "Server S1 (EUM CDN)"
                cleanStreamUrl.contains("s2.vvvv.effedupmovies.com") -> "Server S2 (EUM CDN)"
                cleanStreamUrl.contains("s3.vvvv.effedupmovies.com") -> "Server S3 (EUM CDN)"
                cleanStreamUrl.contains("s4.vvvv.effedupmovies.com") -> "Server S4 (EUM CDN)"
                cleanStreamUrl.contains("s5.vvvv.effedupmovies.com") -> "Server S5 (EUM CDN)"
                else -> {
                    val host = Regex("""https?://([^/]+)""").find(cleanStreamUrl)?.groupValues?.get(1) ?: "EUM"
                    "Server ($host)"
                }
            }

            try {
                // Primary Master M3U8 Link
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = serverName,
                        url = cleanStreamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
                loadedAny = true

                // Also generate sub-stream resolution tiers if master playlist is present
                try {
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = cleanStreamUrl,
                        referer = "$mainUrl/",
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        ),
                        name = serverName
                    ).forEach { subLink ->
                        callback.invoke(subLink)
                    }
                } catch (e: Exception) {
                    // Master link is already emitted
                }
            } catch (e: Exception) {
                // Continue to next stream
            }
        }

        // 5. Check external iframe embeds
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && !src.contains("effedupmovies.com/wp-") && !src.contains("recaptcha")) {
                val cleanIframe = if (src.startsWith("//")) "https:$src" else src
                try {
                    if (loadExtractor(cleanIframe, data, subtitleCallback, callback)) {
                        loadedAny = true
                    }
                } catch (e: Exception) {
                    // Ignore extractor errors
                }
            }
        }

        return loadedAny
    }
}
