// adapter-crypto/build.gradle.kts (Task 5에서 iosMain 의존성이 추가된다)
plugins {
    id("teslable.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
        }
    }
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml"))
}
