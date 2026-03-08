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
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
        // freeRASP — must be last
        maven {
            url = uri("https://europe-west3-maven.pkg.dev/talsec-artifact-repository/freerasp")
            content { includeGroupByRegex("com\\.aheaditec\\..*") }
        }
    }
}

rootProject.name = "K-Wallet"
include(":app")
