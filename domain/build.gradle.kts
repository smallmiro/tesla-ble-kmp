plugins {
    id("teslable.kmp-library")
    alias(libs.plugins.wire)
}

wire {
    sourcePath { srcDir("src/commonMain/proto") }
    kotlin {
        javaInterop = false
    }
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            // Task 6 이후 :testing 추가
        }
    }
    compilerOptions {
        // car_server.proto 의 필드명이 문자 그대로 `other` 인 메시지(BatchRemoveChargeSchedulesAction 등)에서
        // Wire가 생성하는 equals(other_: Any?)가 Any.equals(other: Any?)와 파라미터명이 달라 K2가
        // PARAMETER_NAME_CHANGED_ON_OVERRIDE 경고를 낸다. allWarningsAsErrors 정책은 유지하되 이 진단만
        // 끈다(생성 코드이므로 직접 수정 불가; upstream proto도 무수정 원칙(ADR-0002)이라 손댈 수 없음).
        freeCompilerArgs.add("-Xwarning-level=PARAMETER_NAME_CHANGED_ON_OVERRIDE:disabled")
    }
}
