import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("teslable.kmp-library")
    alias(libs.plugins.skie) // Swift async/await, AsyncSequence, enum 변환 (ADR-0005)
}

kotlin {
    sourceSets {
        commonTest.dependencies { implementation(project(":testing")) }
        commonMain.dependencies {
            api(project(":domain"))
            implementation(project(":application"))
            implementation(project(":adapter-ble"))
            implementation(project(":adapter-crypto"))
            implementation(project(":adapter-storage"))
        }
    }

    // 배포용 XCFramework (ADR-0017): :sdk:assembleTeslableReleaseXCFramework → build/XCFrameworks/release/Teslable.xcframework.
    // 검사와 zip은 tools/release/build-xcframework.sh 가 한다. 샘플 앱과 verify.sh 는 계속 build/bin/<target>/debugFramework 를 쓴다.
    val xcf = XCFramework("Teslable")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Teslable"
            isStatic = true
            export(project(":domain"))
            xcf.add(this)
        }
    }
}

// SKIE가 프레임워크에 넣는 Swift 코드를 빌드한 Mac 밖에서도 쓸 수 있게 한다 (ADR-0017): Swift library evolution(.swiftinterface),
// 정적 프레임워크의 clang 모듈 breadcrumb 제거, 디버그 심볼 소스 경로를 저장소 루트 기준 상대 경로로. 프로젝트 전체 설정이라
// 디버그 프레임워크에도 적용된다.
skie {
    build {
        produceDistributableFramework()
    }
}

detekt { config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml")) }
