pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.screamingsandals.org/public")
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo.screamingsandals.org/public")
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
}

rootProject.name = "nms-mcp"
