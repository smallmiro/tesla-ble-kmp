import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("teslable.kmp-library")
    alias(libs.plugins.skie) // Swift async/await, AsyncSequence, enum 변환 (ADR-0005)
}

// 배포(release) 프레임워크의 Kotlin 디버그 정보에서 빌드한 Mac의 절대 경로를 지운다 (ADR-0017, tools/release/build-xcframework.sh 가 검사).
// K/N -Xdebug-prefix-map 값 하나가 <old>=<new> 한 쌍이다. 저장소 루트는 상대 경로(.)로, 서드파티 klib(Kable, okio, Wire,
// cryptography-kotlin, SKIE 런타임)을 컴파일한 CI의 작업 폴더는 thirdparty 로 바꾼다. 검사가 새 /Users/ 경로를 찾으면 여기에 더한다.
val releaseDebugPrefixMap =
    listOf(
        "-Xdebug-prefix-map=${rootDir.absolutePath}=.",
        "-Xdebug-prefix-map=/Users/runner/work=thirdparty",
        "-Xdebug-prefix-map=/Users/customer/actions-runner-skie/_work=thirdparty",
    )

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
            if (buildType == NativeBuildType.RELEASE) {
                linkTaskProvider.configure { toolOptions.freeCompilerArgs.addAll(releaseDebugPrefixMap) }
            }
        }
    }
}

// SKIE가 프레임워크에 넣는 Swift 코드를 빌드한 Mac 밖에서도 쓸 수 있게 한다 (ADR-0017): Swift library evolution(.swiftinterface),
// 정적 프레임워크의 clang 모듈 breadcrumb 제거. 프로젝트 전체 설정이라 디버그 프레임워크에도 적용된다.
skie {
    build {
        produceDistributableFramework()
        // produceDistributableFramework() 가 함께 켜는 relative source paths 는 끈다. 이 기능은 Kotlin 컴파일 단위를 일부러 빈 경로
        // 매핑으로 만들어(SKIE CodegenPhaseInterceptor.workaroundRelativeDebugPrefixMapBug) 배포 바이너리에 링크 출력의 절대 경로
        // (/Users/<이름>/…/releaseFramework/Teslable.framework)가 남는다. 배포 링크의 상대 경로는 위 releaseDebugPrefixMap 이 만든다.
        enableRelativeSourcePathsInDebugSymbols.set(false)
        // SKIE Swift 오브젝트의 디버그 정보(임포트한 Kotlin 프레임워크와 헤더 경로)도 저장소 루트 기준 상대 경로로 (swiftc -debug-prefix-map).
        freeSwiftCompilerArgs.addAll("-debug-prefix-map", "${rootDir.absolutePath}=.")
    }
    // SKIE는 기본으로 빌드마다 사용 통계를 모아 Mixpanel로 보낸다(skieUploadAnalytics* 태스크). 텔레메트리 금지(AGENTS.md 규칙 6)에
    // 따라 수집과 업로드를 모두 끈다.
    analytics {
        enabled.set(false)
    }
}

detekt { config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml")) }
