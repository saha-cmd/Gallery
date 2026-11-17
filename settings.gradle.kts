pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // UCrop
    }
}

rootProject.name = "Gallery"

include(":app")
include(":core:core-domain")
include(":core:core-data")
include(":core:core-ui")
include(":core:core-security")
include(":feature:feature-gallery")
include(":feature:feature-viewer")
include(":feature:feature-vault")
include(":feature:feature-editor")
