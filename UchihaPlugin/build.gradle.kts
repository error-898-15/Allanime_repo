import com.lagradost.cloudstream3.gradle.tasks.GenerateManifestTask

version = 2

cloudstream {
    description = "Blakite Anime & ZLive - Watch Anime and Live Sports & TV Channels"
    authors = listOf("error-898-15")
    status = 1
    tvTypes = listOf("Anime", "Movie", "Cartoon", "Live")
    language = "en"
    iconUrl = "https://blogger.googleusercontent.com/img/a/AVvXsEgWJNM8v7dkKlHDuBncLOZsjiURJtbxv6de_W_TkIg75W51emlvr-3DATj02j__QUikkzjxhYKv8jYtQp4lc04xObvSTvthIHg_DA0Ud4SRiEUKqralljdfKnUumPN96NEBQwW6y0SpVKcCCPzuIwh8on5sgzjH7BT5PpR6_vp_qS7Qia8OMj04qz-DyMw=s937"
}

tasks.withType<GenerateManifestTask> {
    pluginName.set("Uchiha Plugin")
}

android {
    defaultConfig {
        minSdk = 21
    }
}
