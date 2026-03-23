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
    repositories {
        google()
        mavenCentral()
        // Sherpa-ONNX publishes to GitHub packages
        maven { url = uri("https://jitpack.io") }
        // Also check Maven Central snapshots
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/releases/") }
    }
}

rootProject.name = "EarFlows"
include(":app")
