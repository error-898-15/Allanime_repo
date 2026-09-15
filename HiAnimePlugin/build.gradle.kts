import com.lagradost.cloudstream3.gradle.tasks.GenerateManifestTask

version = 1

cloudstream {
    description = "HiAnime - Watch Anime Online Free with Subbed/Dubbed episodes and multi-quality HLS streaming"
    authors = listOf("error-898-15")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://hianimes.se/assets/logo.png"
}

tasks.withType<GenerateManifestTask> {
    pluginName.set("HiAnime")
}

android {
    defaultConfig {
        minSdk = 21
    }
}
