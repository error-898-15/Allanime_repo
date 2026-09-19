plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "error-898-15/Uchiharepo")
    authors = listOf("error-898-15")
    version = 1
    apiVersion = 1
    description = "NetMirror TV - Stream Netflix, Prime Video, and Hotstar Movies & TV Shows with Original Direct CDN"
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "en"
}

android {
    namespace = "com.uchiharepo.netmirrortv"
    defaultConfig {
        minSdk = 21
    }
}
