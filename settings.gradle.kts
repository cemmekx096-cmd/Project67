dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudstreamPlugins"

// This file sets what projects are included, based on the folder names listed in .github/always_build.json
val alwaysBuildFile = File(rootDir, ".github/always_build.json")
val allowedFolders = alwaysBuildFile.readText()
    .trim()
    .removePrefix("[")
    .removeSuffix("]")
    .split(",")
    .map { it.trim().trim('"') }
    .filter { it.isNotEmpty() }

allowedFolders.forEach { name ->
    val dir = File(rootDir, name)
    if (dir.exists() && File(dir, "build.gradle.kts").exists()) {
        include(name)
    } else {
        println("Skip '$name': folder atau build.gradle.kts tidak ditemukan")
    }
}