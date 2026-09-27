plugins { id("teslable.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies { implementation(project(":domain")) }
        commonTest.dependencies { implementation(project(":testing")) }
    }
}
