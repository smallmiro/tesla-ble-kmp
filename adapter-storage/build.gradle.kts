plugins { id("teslable.kmp-library") }
kotlin { sourceSets { commonMain.dependencies { implementation(project(":domain")) } } }
detekt { config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml")) }
