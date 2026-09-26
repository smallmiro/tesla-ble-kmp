import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
    ":domain" to emptySet(),
    ":application" to setOf(":domain"),
    ":adapter-ble" to setOf(":domain"),
    ":adapter-crypto" to setOf(":domain"),
    ":adapter-storage" to setOf(":domain"),
    ":testing" to setOf(":domain", ":adapter-crypto"),
    ":sdk" to setOf(":domain", ":application", ":adapter-ble", ":adapter-crypto", ":adapter-storage"),
    ":samples:android" to setOf(":sdk"),
)

/** 테스트 구성(이름에 Test 포함)에서는 누구나 :testing 을 쓸 수 있다. */
val testOnlyProjects = setOf(":testing")

/**
 * 실제로 의존성을 선언할 수 있는 구성만 검사한다 (ADR-0001). 이름 접미사만으로는 향후 ksp/kapt 등 새 버킷을
 * 놓칠 수 있으므로, "선언 전용" 구성(canBeDeclared && !canBeResolved && !canBeConsumed — 모든 KMP/AGP/KGP
 * 의존성 버킷이 이 모양이다)이면 이름과 무관하게 검사하고, 접미사 일치는 belt-and-braces로 유지한다.
 * resolvable+consumable인 합성/classpath 구성(`*DependenciesMetadata`, `*Classpath` 등)은 두 조건 모두
 * 해당하지 않아 자연히 제외된다. 단, bcv(binary-compatibility-validator)가 만드는 `bcv-*` 구성은 capability상
 * declared&&!resolved&&!consumed으로 실제 소스셋 버킷과 구분이 안 되어(둘 다 "선언 전용" 모양) 이름으로 별도 제외한다.
 */
val declarableSuffixes = listOf("Implementation", "Api", "CompileOnly", "RuntimeOnly")

/** :domain 이 non-test 구성에서 가져올 수 있는 유일한 외부 의존성 (순수 Kotlin, ADR-0001) */
fun isAllowedForDomain(dependency: ExternalModuleDependency): Boolean = when (dependency.group) {
    "org.jetbrains.kotlin" -> true
    "org.jetbrains.kotlinx" -> dependency.name.startsWith("kotlinx-coroutines")
    "com.squareup.wire" -> dependency.name == "wire-runtime"
    "com.squareup.okio" -> true
    else -> false
}

gradle.projectsEvaluated {
    rootProject.subprojects.forEach { sub ->
        // 레지스트리에 없는 서브프로젝트는 project(...) 의존성을 선언해서는 안 된다.
        val permitted = allowedProjectDependencies[sub.path]
        sub.configurations.configureEach {
            // bcv(binary-compatibility-validator)는 자체 툴체인용으로 pure-declarable 구성(bcv-*)을 만드는데,
            // capability만으로는 진짜 소스셋 버킷과 구분되지 않는다. 이름으로 명시 제외한다.
            if (name.startsWith("bcv-")) return@configureEach
            val isPureDeclarable = isCanBeDeclared && !isCanBeResolved && !isCanBeConsumed
            val hasKnownSuffix = declarableSuffixes.any { suffix -> name.endsWith(suffix, ignoreCase = true) }
            if (!isPureDeclarable && !hasKnownSuffix) return@configureEach
            val configurationName = name
            val isTestConfiguration = configurationName.contains("Test", ignoreCase = true)
            dependencies.withType<ProjectDependency>().configureEach {
                val target = path
                if (permitted == null) {
                    throw GradleException(
                        "Architecture boundary violation: ${sub.path} ($configurationName) -> $target. " +
                            "${sub.path} is not registered in allowedProjectDependencies and must not declare " +
                            "project dependencies. See ADR-0001 / docs/workflow.md §4.2",
                    )
                }
                val ok = target == sub.path || target in permitted || (isTestConfiguration && target in testOnlyProjects)
                if (!ok) {
                    throw GradleException(
                        "Architecture boundary violation: ${sub.path} ($configurationName) -> $target. " +
                            "Allowed: $permitted (+ :testing in test configurations). See ADR-0001 / docs/workflow.md §4.2",
                    )
                }
            }
            if (sub.path == ":domain" && !isTestConfiguration) {
                dependencies.withType<ExternalModuleDependency>().configureEach {
                    if (!isAllowedForDomain(this)) {
                        throw GradleException(":domain must stay pure Kotlin. Forbidden dependency: $group:$name (ADR-0001)")
                    }
                }
            }
        }
    }
}
