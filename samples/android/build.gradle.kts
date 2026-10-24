plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.detekt)
}

android {
    namespace = "dev.mirolab.teslable.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.mirolab.teslable.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":sdk"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar의 org.json은 단위 테스트에서 "not mocked"이므로 JSON 검증용으로 실제 구현을 쓴다.
    testImplementation(libs.org.json)
}

// 샘플에도 라이브러리와 같은 스타일·정적 분석 게이트를 건다(NFR-015, #99). 라이브러리 전용 규칙(explicitApi, forbiddenTokens)은
// 적용하지 않는다. 샘플 전용 완화는 config/detekt/detekt-samples.yml 에 이유와 함께 둔다.
detekt {
    source.setFrom(files("src"))
    config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-samples.yml"))
    buildUponDefaultConfig = true
}

kotlinter {
    ignoreFormatFailures = false
    ignoreLintFailures = false
}
