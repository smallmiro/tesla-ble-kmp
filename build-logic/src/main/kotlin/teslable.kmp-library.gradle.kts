import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jmailen.kotlinter")
    id("io.gitlab.arturbosch.detekt")
    id("dev.whyoleg.cryptography") // Apple 테스트/프레임워크 바이너리에 Swift 링커 옵션 (ADR-0004)
}

val libs = the<LibrariesForLibs>()

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()

    android {
        namespace = "io.github.smallmiro.teslable." + project.name.replace('-', '.')
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest { }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM과 Android가 공유하는 JCA 코드용 중간 소스셋
        val jvmCommonMain = create("jvmCommonMain") { dependsOn(commonMain.get()) }
        val jvmCommonTest = create("jvmCommonTest") { dependsOn(commonTest.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
        jvmTest.get().dependsOn(jvmCommonTest)
        getByName("androidHostTest").dependsOn(jvmCommonTest)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // iOS 시뮬레이터 테스트 기기 (Xcode 26.6 / iOS 26.5 런타임)
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set("iPhone 17")
    }
}

cryptography {
    configureSwiftLinkerOpts = true
}

detekt {
    source.setFrom(files("src"))
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

kotlinter {
    ignoreFormatFailures = false
    ignoreLintFailures = false
}

// detekt's type-resolution-dependent rules (ForbiddenMethodCall, UnsafeCallOnNullableType) don't fire on the
// plain `detekt` task wired into `check`, so enforce the same bans with a lightweight text scan (NFR-006, ADR-0001,
// docs/workflow.md §4.4). Main source sets only; test sources are exempt.
tasks.register("forbiddenTokens") {
    group = "verification"
    description = "Fails if main sources contain !!, println(, runBlocking, or GlobalScope."
    val mainSources = fileTree(projectDir) { include("src/*Main/**/*.kt") }
    inputs.files(mainSources)
    doLast {
        val forbidden = listOf("!!", "println(", "runBlocking", "GlobalScope")
        val violations = mutableListOf<String>()
        // 줄 번호를 보존하기 위해 매치된 개행 수만큼의 개행으로 치환한다 (block comment, raw string 먼저 제거).
        fun blank(text: String, pattern: Regex) = pattern.replace(text) { "\n".repeat(it.value.count { c -> c == '\n' }) }
        mainSources.forEach { file ->
            val withoutBlockComments = blank(file.readText(), Regex("/\\*[\\s\\S]*?\\*/"))
            val withoutRawStrings = blank(withoutBlockComments, Regex("\"\"\"[\\s\\S]*?\"\"\""))
            withoutRawStrings.lines().forEachIndexed { index, rawLine ->
                val noStrings = rawLine.replace(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), "\"\"")
                val noComments = noStrings.replace(Regex("//.*$"), "")
                forbidden.forEach { token ->
                    if (noComments.contains(token)) {
                        violations += "${file.relativeTo(projectDir)}:${index + 1}: forbidden token '$token'"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("forbiddenTokens found violations:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn("forbiddenTokens") }
