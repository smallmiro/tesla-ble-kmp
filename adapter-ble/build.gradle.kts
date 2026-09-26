plugins { id("teslable.kmp-library") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(libs.kable) // M3에서 사용. 지금은 해석만 확인
        }
    }
}
detekt { config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml")) }
