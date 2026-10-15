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

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Teslable"
            isStatic = true
            export(project(":domain"))
        }
    }
}

detekt { config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml")) }
