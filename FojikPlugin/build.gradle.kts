plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.uchiharepo.fojik"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
    }
}

cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "error-898-15/Uchiharepo")
    authors = listOf("error-898-15")
    status = 1
}

dependencies {
    val cloudstreamApiVersion = "pre-release"
    implementation("com.github.recloudstream:cloudstream:$cloudstreamApiVersion")
    implementation("org.jsoup:jsoup:1.17.2")
}
