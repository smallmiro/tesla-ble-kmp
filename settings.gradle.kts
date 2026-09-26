pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // JDK 17 툴체인을 자동으로 받는다 (이 머신에는 JDK 25만 설치되어 있음)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tesla-ble-kmp"

include(":domain")
include(":application")
include(":adapter-ble")
include(":adapter-crypto")
include(":adapter-storage")
include(":sdk")
include(":testing")
include(":samples:android")
