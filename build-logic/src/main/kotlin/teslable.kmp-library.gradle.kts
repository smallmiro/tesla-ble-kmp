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

/**
 * Kotlin 어휘를 최소한으로 스캔해 문자열/문자 리터럴/주석(중첩 block comment 포함) 내부를 공백/개행으로 지우고
 * 실제 코드만 남긴다. 개행은 그대로 유지해 라인 번호가 어긋나지 않게 한다. 정규식 두 패스로는 문자열 안의
 * 미완성 블록 주석 시작 토큰이 문자열 경계를 벗어나 실제 코드를 집어삼킬 수 있어(false negative), 왼쪽에서
 * 오른쪽으로 한 번에 스캔한다. 문자열 템플릿(${…}) 내부는 문자열의 일부로 취급해 별도로 파싱하지 않는다(허용된 한계).
 */
private val BLOCK_OPEN = "/" + "*"
private val BLOCK_CLOSE = "*" + "/"

private fun stripNonCode(text: String): String {
    val out = StringBuilder(text.length)
    fun keep(from: Int, until: Int) {
        for (j in from until until) out.append(if (text[j] == '\n') '\n' else ' ')
    }
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("\"\"\"", i) -> {
                val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                keep(i, end); i = end
            }
            text[i] == '"' || text[i] == '\'' -> {
                val quote = text[i]
                var j = i + 1
                while (j < text.length && text[j] != quote) {
                    j += if (text[j] == '\\' && j + 1 < text.length) 2 else 1
                }
                val end = (j + 1).coerceAtMost(text.length)
                keep(i, end); i = end
            }
            text.startsWith("//", i) -> {
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                keep(i, end); i = end
            }
            text.startsWith(BLOCK_OPEN, i) -> {
                var depth = 1
                var j = i + 2
                while (j < text.length && depth > 0) {
                    when {
                        text.startsWith(BLOCK_OPEN, j) -> { depth++; j += 2 }
                        text.startsWith(BLOCK_CLOSE, j) -> { depth--; j += 2 }
                        else -> j++
                    }
                }
                keep(i, j); i = j
            }
            else -> { out.append(text[i]); i++ }
        }
    }
    return out.toString()
}

// detekt's type-resolution-dependent rules (ForbiddenMethodCall, UnsafeCallOnNullableType) don't fire on the
// plain `detekt` task wired into `check`, so enforce the same bans with a lightweight lexical scan (NFR-006,
// ADR-0001, docs/workflow.md §4.4). Main source sets only; test sources are exempt.
tasks.register("forbiddenTokens") {
    group = "verification"
    description = "Fails if main sources contain !!, println(, runBlocking, or GlobalScope."
    val mainSources = fileTree(projectDir) { include("src/*Main/**/*.kt") }
    inputs.files(mainSources)
    doLast {
        val forbidden = listOf("!!", "println(", "runBlocking", "GlobalScope")
        val violations = mutableListOf<String>()
        mainSources.forEach { file ->
            stripNonCode(file.readText()).lines().forEachIndexed { index, line ->
                forbidden.forEach { token ->
                    if (line.contains(token)) {
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
