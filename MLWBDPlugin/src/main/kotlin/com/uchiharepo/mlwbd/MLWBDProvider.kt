package com.uchiharepo.mlwbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class MLWBDProvider : MainAPI() {
    override var mainUrl = "https://fojik.site"
    override var name = "MLWBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val MIRROR_DOMAINS = listOf(
            "https://fojik.site",
            "https://mlwbd.click",
            "https://mlwbd.cc"
        )
    }

    private suspend fun getDocument(url: String, referer: String = mainUrl): Document {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
        )
        return try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            val currentDomain = URI(url).host
            var lastErr: Exception = e
            for (mirror in MIRROR_DOMAINS) {
                val mirrorHost = URI(mirror).host
                if (mirrorHost.equals(currentDomain, ignoreCase = true)) continue
                val fallbackUrl = url.replace("https://$currentDomain", mirror)
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
        "$mainUrl/movie/page/" to "Latest Movies",
        "$mainUrl/tvshows/page/" to "TV Series",
        "$mainUrl/trending/page/" to "Trending Now",
        "$mainUrl/genre/bengali-dubbed/page/" to "Bengali Dubbed",
        "$mainUrl/genre/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/genre/south-indian/page/" to "South Indian (Hindi)",
        "$mainUrl/genre/hollywood/page/" to "Hollywood Movies",
        "$mainUrl/genre/bollywood/page/" to "Bollywood Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = getDocument(url)
        val home = document.select("article.item, article.post, div.result-item, .items article").mapNotNull {
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
        if (cleaned.contains("cropped-", ignoreCase = true) && cleaned.contains("icon", ignoreCase = true)) {
            return null
        }
        return cleaned.replace("/w185/", "/w500/").replace("/w342/", "/w500/")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, .title a, .entry-title, h2")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val href = this.selectFirst("a[href]")?.attr("href") ?: return null
        if (!href.startsWith("http") || href.contains("/category/") || href.contains("/genre/") || href.contains("/tag/")) {
            return null
        }

        val posterUrl = extractImageUrl(this)
        val isMovie = href.contains("/movie/") || (!href.contains("/tvshows/") && !href.contains("/series/"))

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encoded"
        val document = getDocument(searchUrl)
        return document.select("div.result-item, article.item, article.post, .items article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val title = document.selectFirst("div.data h1, h1.entry-title, h1")?.text()?.trim()
            ?: "Unknown Title"
        val posterUrl = extractImageUrl(document.selectFirst("div.poster, .data .poster, .sheader .poster"))
        val plot = document.selectFirst("div.wp-content p, div#info .sinopsis p, .sinopsis")?.text()?.trim()
        val yearText = document.selectFirst(".extra span.date, .extra span.country + span, .date")?.text()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find("$yearText $title")?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select("div.sgenres a, .genres a").map { it.text().trim() }
        val rating = document.selectFirst(".dt_rating_vgs, .rating")?.text()?.trim()

        val episodeElements = document.select("#seasons .episodios li, .episodios li")
        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = ep.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val epNum = ep.selectFirst(".numerando")?.text()?.trim() ?: ""
                val sMatch = Regex("""(\d+)\s*-\s*(\d+)""").find(epNum)
                val season = sMatch?.groupValues?.get(1)?.toIntOrNull()
                val episode = sMatch?.groupValues?.get(2)?.toIntOrNull()
                val epTitle = ep.selectFirst(".episodiotitle a, a")?.text()?.trim()
                val epThumb = extractImageUrl(ep)

                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = season
                    this.episode = episode
                    this.posterUrl = epThumb
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.rating = rating?.toIntOrNull()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
                this.rating = rating?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        var anyFound = false

        // 1. Parse MLWBD's Original Download / Streaming Quality Buttons
        val linkButtons = document.select("a[href*='hubcloud'], a[href*='vifix'], a[href*='gdflix'], a[href*='fastdrive'], a[href*='pixeldrain'], a[href*='drive.google'], a.btn-download, a.dlink, div.download-links a, div.wp-content a[href*='http']")
        for (btn in linkButtons) {
            val href = btn.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("facebook.com") || href.contains("t.me")) continue

            val btnText = btn.text().trim()
            val parentText = btn.parent()?.text()?.trim() ?: ""
            val quality = determineQuality("$btnText $parentText")

            if (href.contains("hubcloud") || href.contains("vifix.site")) {
                val ok = extractHubCloud(href, quality, callback)
                if (ok) anyFound = true
            } else if (href.contains("gdflix") || href.contains("fastdrive")) {
                val ok = extractGDFlix(href, quality, callback)
                if (ok) anyFound = true
            } else if (href.contains("pixeldrain.com")) {
                val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                if (id.isNotEmpty()) {
                    val streamUrl = "https://pixeldrain.com/api/file/$id"
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - PixelDrain",
                            name = "$name - PixelDrain (${quality.first})",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://pixeldrain.com/"
                            this.quality = quality.second
                        }
                    )
                    anyFound = true
                }
            } else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - Direct",
                        name = "$name - Direct Stream (${quality.first})",
                        url = href,
                        type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = quality.second
                    }
                )
                anyFound = true
            } else {
                try {
                    loadExtractor(href, data, subtitleCallback, callback)
                    anyFound = true
                } catch (_: Exception) { }
            }
        }

        // 2. Parse DooPlay Online Player options (if available)
        val playerOptions = document.select("li.dooplay_player_option, ul#playeroptions li, .options li")
        for (option in playerOptions) {
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()

            if (post.isNotEmpty() && nume.isNotEmpty()) {
                val playerApiUrl = "$mainUrl/wp-json/dooplayer/v2/$post/$type/$nume"
                try {
                    val resJson = app.get(
                        playerApiUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "Accept" to "application/json, text/javascript, */*"
                        )
                    ).text
                    val dooRes = parseJson<DooPlayerResponse>(resJson)
                    val embedUrl = dooRes.embed_url
                    if (!embedUrl.isNullOrBlank()) {
                        val cleanedEmbed = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
                        if (cleanedEmbed.contains("hubcloud")) {
                            extractHubCloud(cleanedEmbed, Pair("1080p", Qualities.P1080.value), callback)
                            anyFound = true
                        } else {
                            loadExtractor(cleanedEmbed, mainUrl, subtitleCallback, callback)
                            anyFound = true
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        // 3. Fallback: Parse any raw iframes
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("telegram")) {
                val fullSrc = if (src.startsWith("//")) "https:$src" else src
                try {
                    loadExtractor(fullSrc, data, subtitleCallback, callback)
                    anyFound = true
                } catch (_: Exception) { }
            }
        }

        return anyFound
    }

    // --- Original Server 1: HubCloud Direct Stream Extractor ---
    private suspend fun extractHubCloud(
        url: String,
        quality: Pair<String, Int>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var targetUrl = url
            if (targetUrl.contains("vifix.site/hubcloud/")) {
                val id = targetUrl.substringAfter("hubcloud/").substringBefore("?").trim()
                targetUrl = "https://hubcloud.one/drive/$id"
            }

            val doc1 = app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            ).document

            val downloadBtn = doc1.selectFirst("a#download, a.btn-success, a[href*='hubcloud.php']")
            val nextUrl = downloadBtn?.attr("href") ?: targetUrl
            val fullNextUrl = if (nextUrl.startsWith("/")) {
                val base = URI(targetUrl)
                "${base.scheme}://${base.host}$nextUrl"
            } else nextUrl

            val doc2 = if (fullNextUrl != targetUrl) {
                app.get(
                    fullNextUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to targetUrl)
                ).document
            } else doc1

            var extracted = false
            val links = doc2.select("a[href]")
            for (link in links) {
                val href = link.attr("href").trim()

                if (href.contains("r2.dev") || href.contains("cloudflare")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - HubCloud FastCDN",
                            name = "$name - FastCDN (${quality.first})",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = fullNextUrl
                            this.quality = quality.second
                        }
                    )
                    extracted = true
                } else if (href.contains("pixeldrain.com")) {
                    val id = href.substringAfter("/u/").substringAfter("/file/").substringBefore("?").trim()
                    if (id.isNotEmpty()) {
                        val streamUrl = "https://pixeldrain.com/api/file/$id"
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - PixelDrain",
                                name = "$name - PixelDrain (${quality.first})",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://pixeldrain.com/"
                                this.quality = quality.second
                            }
                        )
                        extracted = true
                    }
                } else if (href.contains("workers.dev")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - Worker CDN",
                            name = "$name - Worker CDN (${quality.first})",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = fullNextUrl
                            this.quality = quality.second
                        }
                    )
                    extracted = true
                } else if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$name - HubCloud",
                            name = "$name - Direct (${quality.first})",
                            url = href,
                            type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = fullNextUrl
                            this.quality = quality.second
                        }
                    )
                    extracted = true
                }
            }
            extracted
        } catch (e: Exception) {
            false
        }
    }

    // --- Original Server 2: GDFlix / FastDrive Extractor ---
    private suspend fun extractGDFlix(
        url: String,
        quality: Pair<String, Int>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).document
            val streamBtn = doc.selectFirst("a[href*='drive.google'], a.btn-primary, a[href*='file']")
            val streamHref = streamBtn?.attr("href") ?: return false

            if (streamHref.contains("drive.google") || streamHref.contains("pixeldrain")) {
                loadExtractor(streamHref, url, {}, callback)
                true
            } else if (streamHref.endsWith(".mp4") || streamHref.endsWith(".mkv")) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name - GDFlix",
                        name = "$name - GDFlix (${quality.first})",
                        url = streamHref,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = quality.second
                    }
                )
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun determineQuality(text: String): Pair<String, Int> {
        val lower = text.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Pair("4K 2160p", Qualities.P2160.value)
            lower.contains("1080") -> Pair("1080p FHD", Qualities.P1080.value)
            lower.contains("720") -> Pair("720p HD", Qualities.P720.value)
            lower.contains("480") -> Pair("480p SD", Qualities.P480.value)
            lower.contains("360") -> Pair("360p", Qualities.P360.value)
            else -> Pair("HD", Qualities.P720.value)
        }
    }

    data class DooPlayerResponse(
        val embed_url: String? = null,
        val type: String? = null
    )
}
