// testing/build.gradle.kts — 테스트 픽스처 모듈. 운영 모듈의 main 소스셋에서는 참조 금지 (경계 플러그인이 막는다)
plugins {
    id("teslable.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":adapter-crypto"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
