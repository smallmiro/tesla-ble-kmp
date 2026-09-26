plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.wire) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.cryptography) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.bcv)
    id("teslable.boundaries")
}

apiValidation {
    // 샘플과 테스트 픽스처는 공개 API가 아니다
    ignoredProjects.addAll(listOf("samples", "android", "testing"))
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
