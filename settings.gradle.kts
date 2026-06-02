pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ⚠️ TarsosDSP Android 포크는 JitPack에서 제공됨
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "VocalMaster"
include(":app")
