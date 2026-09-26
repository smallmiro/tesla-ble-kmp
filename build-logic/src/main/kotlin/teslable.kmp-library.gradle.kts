import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

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
// plain `detekt` task wired into `check`, so enforce the same bans with a lightweight lexical scan (NFR-006,
// ADR-0001, docs/workflow.md §4.4). Main source sets only; test sources are exempt.
//
// Configuration Cache: the scan itself (`stripNonCode`/`scan`) lives in `ForbiddenTokenScanner` (a plain .kt
// file in this build-logic module, not a precompiled script plugin) because a top-level function declared in
// this .gradle.kts file compiles as a member of the script's own class, and calling it from `doLast` would
// capture that script instance (a "Gradle script object reference") — one of the two failures this task used
// to produce under `--configuration-cache`. The other was capturing the live `FileTree` returned by
// `fileTree(...)`, which retains an internal reference to this `Project` (serialized as `DefaultProject`).
// The fix: resolve the module directory and the matching files to plain `File`/`List<File>` values here at
// configuration time, and have `doLast` reference only those captured plain values plus the external
// `ForbiddenTokenScanner` object — never `project`, `projectDir`, or the live `FileTree`.
tasks.register("forbiddenTokens") {
    group = "verification"
    description = "Fails if main sources contain !!, println(, runBlocking, or GlobalScope."
    val moduleDir: File = layout.projectDirectory.asFile
    val mainSourceFiles: List<File> = fileTree(moduleDir) { include("src/*Main/**/*.kt") }.files.toList()
    inputs.files(mainSourceFiles)
    doLast {
        val violations = ForbiddenTokenScanner.scan(mainSourceFiles, moduleDir)
        if (violations.isNotEmpty()) {
            throw GradleException("forbiddenTokens found violations:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn("forbiddenTokens") }
