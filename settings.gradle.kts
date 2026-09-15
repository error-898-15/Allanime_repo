rootProject.name = "Uchiharepo"

// Automatically include subprojects that have build.gradle.kts
File(rootDir, ".").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
    if (File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}
