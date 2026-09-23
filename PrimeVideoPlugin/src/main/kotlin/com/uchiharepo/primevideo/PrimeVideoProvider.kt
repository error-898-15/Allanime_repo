package com.uchiharepo.primevideo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder
import java.util.Base64

class PrimeVideoProvider : MainAPI() {
    override var name = "Prime Video"
    override var mainUrl = "https://tv.imgcdn.kim"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val mapper = jacksonObjectMapper()
    private val NEW_TV_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0"
    private var newTvApi: String? = null

    private suspend fun resolveNewTvApi(): String {
        if (!newTvApi.isNullOrBlank()) return newTvApi!!
        val endpoints = listOf(
            "https://mobiledetects.com/checknewtv.php",
            "https://mobiledetect.app/checknewtv.php"
        )
        for (url in endpoints) {
            try {
                val res = app.get(url, headers = mapOf("User-Agent" to NEW_TV_UA)).text
                val json = mapper.readTree(res)
                val hash = json.get("token_hash")?.asText()
                if (!hash.isNullOrBlank()) {
                    val decoded = String(Base64.getDecoder().decode(hash), Charsets.UTF_8)
                    newTvApi = decoded
                    return decoded
                }
            } catch (_: Exception) {}
        }
        return "https://tv.imgcdn.kim"
    }

    // ১. হোমপেজ ইমপ্লিমেন্টেশন (This operation is not implemented এর সমাধান)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = resolveNewTvApi()
        val headers = mapOf(
            "User-Agent" to NEW_TV_UA,
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "Accept" to "application/json, text/plain, */*",
            "Ott" to "pv"
        )

        val res = app.get("$base/newtv/main.php", headers = headers).text
        val json = mapper.readTree(res)
        val homePageList = mutableListOf<HomePageList>()

        // ব্যানার / স্লাইডার আইটেম
        val sliderNode = json.get("slider")
        if (sliderNode != null && sliderNode.isArray) {
            val sliderItems = mutableListOf<SearchResponse>()
            for (s in sliderNode) {
                val id = s.get("id")?.asText() ?: continue
                val title = s.get("title")?.asText()?.takeIf { it.isNotBlank() } ?: "Featured"
                val img = s.get("img")?.asText() ?: "https://imgcdn.kim/poster/h/$id.jpg"
                sliderItems.add(newMovieSearchResponse(title, "$base/post/$id", TvType.Movie) {
                    this.posterUrl = img
                })
            }
            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("Featured Spotlight", sliderItems, isHorizontalImages = true))
            }
        }

        // ক্যাটাগরি ও রো আইটেম
        val postNode = json.get("post")
        if (postNode != null && postNode.isArray) {
            for (section in postNode) {
                val categoryName = section.get("cate")?.asText() ?: continue
                val ids = section.get("ids")?.asText()?.split(",") ?: emptyList()
                val items = mutableListOf<SearchResponse>()

                for (id in ids.take(15)) {
                    val cleanId = id.trim()
                    if (cleanId.isEmpty()) continue
                    val poster = "https://imgcdn.kim/poster/341/$cleanId.jpg"
                    items.add(newMovieSearchResponse("", "$base/post/$cleanId", TvType.Movie) {
                        this.posterUrl = poster
                    })
                }

                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(categoryName, items))
                }
            }
        }

        return HomePageResponse(homePageList)
    }

    // ২. সার্চ মেথড
    override suspend fun search(query: String): List<SearchResponse> {
        val base = resolveNewTvApi()
        val encQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$base/newtv/search.php?s=$encQuery"
        val headers = mapOf(
            "User-Agent" to NEW_TV_UA,
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "Accept" to "application/json, text/plain, */*",
            "Ott" to "pv"
        )

        val res = app.get(searchUrl, headers = headers).text
        val json = mapper.readTree(res)
        val list = mutableListOf<SearchResponse>()
        val results = json.get("searchResult") ?: return emptyList()

        for (item in results) {
            val id = item.get("id")?.asText() ?: continue
            val title = item.get("t")?.asText() ?: "Unknown"
            val poster = "https://imgcdn.kim/poster/341/$id.jpg"
            list.add(newTvSeriesSearchResponse(title, "$base/post/$id", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return list
    }

    // ৩. মুভি ও টিভি সিরিজ ডিটেইলস এবং এপিসোড লোড মেথড
    override suspend fun load(url: String): LoadResponse {
        val base = resolveNewTvApi()
        val id = url.substringAfterLast("/").substringBefore("?")
        val postUrl = "$base/newtv/post.php?id=$id"
        val headers = mapOf(
            "User-Agent" to NEW_TV_UA,
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "Accept" to "application/json, text/plain, */*",
            "Ott" to "pv"
        )

        val res = app.get(postUrl, headers = headers).text
        val json = mapper.readTree(res)
        val title = json.get("title")?.asText()?.takeIf { it.isNotBlank() } ?: "Prime Video"
        val desc = json.get("desc")?.asText()
        val year = json.get("year")?.asText()?.toIntOrNull()
        val poster = "https://imgcdn.kim/poster/h/$id.jpg"

        val seasonsNode = json.get("season")
        if (seasonsNode != null && seasonsNode.isArray && seasonsNode.size() > 0) {
            val episodesList = mutableListOf<Episode>()
            for (season in seasonsNode) {
                val sId = season.get("id")?.asText() ?: continue
                val epReqUrl = "$base/newtv/episodes.php?id=$sId"
                val epRes = app.get(epReqUrl, headers = headers).text
                val epJson = mapper.readTree(epRes)
                val epArray = epJson.get("episodes") ?: continue

                for (ep in epArray) {
                    val epId = ep.get("id")?.asText() ?: continue
                    val epName = ep.get("t")?.asText() ?: "Episode"
                    val epNum = ep.get("ep")?.asText()?.toIntOrNull()
                    episodesList.add(newEpisode("$base/play/$epId") {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = "https://imgcdn.kim/epimg/150/$epId.jpg"
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = desc
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, "$base/play/$id") {
            this.posterUrl = poster
            this.plot = desc
            this.year = year
        }
    }

    // ৪. ভিডিও লিংক লোড মেথড
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = resolveNewTvApi()
        val id = data.substringAfterLast("/").substringBefore("?").substringBefore("&")
        val playerUrl = "$base/newtv/player.php?id=$id"
        val headers = mapOf(
            "User-Agent" to NEW_TV_UA,
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "Accept" to "application/json, text/plain, */*",
            "Ott" to "pv",
            "Cache-Control" to "no-cache"
        )

        val resText = app.get(playerUrl, headers = headers).text
        val json = mapper.readTree(resText)
        val videoLink = json.get("video_link")?.asText()

        if (!videoLink.isNullOrBlank()) {
            val referer = json.get("referer")?.asText() ?: "https://net52.cc"

            StreamProxy.start(mapOf(
                "User-Agent" to NEW_TV_UA,
                "Referer" to referer
            ))

            val proxiedUrl = StreamProxy.localUrl(videoLink)

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name · HD",
                    url = proxiedUrl,
                    referer = referer,
                    quality = Qualities.P1080.value,
                    type = if (videoLink.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                )
            )
            return true
        }
        return false
    }
}
