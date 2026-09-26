plugins {
    id("teslable.kmp-library")
    alias(libs.plugins.skie) // Swift async/await, AsyncSequence, enum 변환 (ADR-0005)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            implementation(project(":application"))
            implementation(project(":adapter-ble"))
            implementation(project(":adapter-crypto"))
            implementation(project(":adapter-storage"))
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Teslable"
            isStatic = true
            export(project(":domain"))
        }
    }
}
