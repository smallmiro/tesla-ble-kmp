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
            implementation(project(":testing"))
        }
    }
    compilerOptions {
        // car_server.proto 의 필드명이 문자 그대로 `other` 인 메시지(BatchRemoveChargeSchedulesAction 등)에서
        // Wire가 생성하는 equals(other_: Any?)가 Any.equals(other: Any?)와 파라미터명이 달라 K2가
        // PARAMETER_NAME_CHANGED_ON_OVERRIDE 경고를 낸다. allWarningsAsErrors 정책은 유지하되 이 진단만
        // 끈다(생성 코드이므로 직접 수정 불가; upstream proto도 무수정 원칙(ADR-0002)이라 손댈 수 없음).
        freeCompilerArgs.add("-Xwarning-level=PARAMETER_NAME_CHANGED_ON_OVERRIDE:disabled")
        // Teslemetry 스냅샷(ADR-0002 개정 2026-10-18)의 repeated packed 필드(VehicleDataAck, VehicleState 등)에서
        // Wire 7.0.4가 `mutableList!!`를 생성해 K2가 UNNECESSARY_NOT_NULL_ASSERTION 경고를 낸다. 생성 코드라 고칠 수 없어
        // 이 진단만 끈다(손으로 쓴 :domain 코드의 `!!`는 detekt UnsafeCallOnNullableType 등이 계속 본다).
        freeCompilerArgs.add("-Xwarning-level=UNNECESSARY_NOT_NULL_ASSERTION:disabled")
    }
}
