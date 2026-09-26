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

/** 실제로 의존성을 선언할 수 있는 구성만 검사한다 (bcv/detekt 등 플러그인 내부용 합성 구성은 제외, ADR-0001). */
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
            val configurationName = name
            if (declarableSuffixes.none { suffix -> configurationName.endsWith(suffix, ignoreCase = true) }) return@configureEach
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
