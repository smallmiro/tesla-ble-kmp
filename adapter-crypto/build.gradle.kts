// adapter-crypto/build.gradle.kts
plugins {
    id("teslable.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
        }
        iosMain.dependencies {
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.cryptokit) // AES-GCM만 사용 (ADR-0004)
        }
    }
}

detekt {
    // 어댑터는 platform.* 임포트가 필요하다
    config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml"))
}
