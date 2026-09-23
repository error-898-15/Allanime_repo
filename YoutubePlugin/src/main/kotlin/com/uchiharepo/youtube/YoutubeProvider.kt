package com.uchiharepo.youtube

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val SEARCH_API = "https://www.youtube.com/youtubei/v1/search"
        private const val OEMBED_API = "https://www.youtube.com/oembed"

        private val mapper = ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override val mainPage = mainPageOf(
        "Trending" to "Trending Now",
        "Popular Music 2024" to "Music Hits",
        "Movie Trailers 2024" to "Movies & Trailers",
        "Gaming" to "Gaming Highlights",
        "News" to "Top News",
        "Science Documentary" to "Documentaries",
        "Anime Official" to "Anime & Animation",
        "Podcast" to "Podcasts & Talks",
        "Live Stream" to "Live 24/7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val query = request.data
        val items = fetchSearchResults(query)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchSearchResults(query)
    }

    private suspend fun fetchSearchResults(query: String): List<SearchResponse> {
        return try {
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB",
                        "clientVersion" to "2.20240101.00.00"
                    )
                ),
                "query" to query.trim()
            )

            val response = app.post(
                SEARCH_API,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to USER_AGENT,
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                ),
                json = payload
            )

            if (!response.isSuccessful) return emptyList()

            val rootNode = mapper.readTree(response.text)
            val results = mutableListOf<SearchResponse>()
            extractVideoRenderers(rootNode, results)
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractVideoRenderers(node: JsonNode, results: MutableList<SearchResponse>) {
        if (node.isObject) {
            val vr = node.get("videoRenderer")
            if (vr != null && vr.isObject) {
                val videoId = vr.path("videoId").asText("")
                if (videoId.isNotBlank()) {
                    val titleRuns = vr.path("title").path("runs")
                    val title = if (titleRuns.isArray && titleRuns.size() > 0) {
                        titleRuns.get(0).path("text").asText("YouTube Video")
                    } else {
                        vr.path("title").path("simpleText").asText("YouTube Video")
                    }

                    val thumbs = vr.path("thumbnail").path("thumbnails")
                    val posterUrl = if (thumbs.isArray && thumbs.size() > 0) {
                        thumbs.get(thumbs.size() - 1).path("url").asText()
                    } else {
                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    }

                    val url = "https://www.youtube.com/watch?v=$videoId"
                    results.add(newMovieSearchResponse(title, url, TvType.Others) {
                        this.posterUrl = posterUrl
                    })
                }
            }
            val fields = node.fields()
            while (fields.hasNext()) {
                extractVideoRenderers(fields.next().value, results)
            }
        } else if (node.isArray) {
            val elements = node.elements()
            while (elements.hasNext()) {
                extractVideoRenderers(elements.next(), results)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = extractVideoId(url)
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

        var title = "YouTube Video ($videoId)"
        var author = ""
        var posterUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        try {
            val oembedUrl = "$OEMBED_API?url=$watchUrl&format=json"
            val resp = app.get(
                oembedUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            )

            if (resp.isSuccessful) {
                val json = mapper.readTree(resp.text)
                title = json.path("title").asText(title)
                author = json.path("author_name").asText("")
                posterUrl = json.path("thumbnail_url").asText(posterUrl)
            }
        } catch (e: Exception) {
            // Keep safe fallbacks
        }

        return newMovieLoadResponse(
            name = title,
            url = watchUrl,
            type = TvType.Others,
            data = videoId
        ) {
            this.posterUrl = posterUrl
            this.plot = if (author.isNotBlank()) "Channel: $author\nWatch directly on YouTube." else "Watch on YouTube."
            if (author.isNotBlank()) {
                this.tags = listOf(author, "YouTube")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = extractVideoId(data)
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

        // Invokes CloudStream's native YouTube extractor engine
        return loadExtractor(
            watchUrl,
            "$mainUrl/",
            subtitleCallback,
            callback
        )
    }

    private fun extractVideoId(input: String): String {
        val clean = input.trim()
        if (clean.length == 11 && !clean.contains("/") && !clean.contains("?")) {
            return clean
        }
        val match = Regex("(?:v=|/videos/|embed/|youtu\\.be/|/shorts/|^)([a-zA-Z0-9_-]{11})")
            .find(clean)
        return match?.groupValues?.get(1) ?: clean
    }
}
