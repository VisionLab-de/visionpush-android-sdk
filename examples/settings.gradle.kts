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
        // Local SDK build — run `./gradlew publishToMavenLocal` in sdks/android first.
        mavenLocal()
    }
}

rootProject.name = "VisionPushDemoApp"
include(":app")
