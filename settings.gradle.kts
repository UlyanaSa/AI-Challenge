rootProject.name = "deepseek-chat-kmp"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
            }
        }
        mavenCentral()
    }
}

include(":app:androidApp")
include(":app:shared")
include(":app:webApp")
include(":core")
include(":server")