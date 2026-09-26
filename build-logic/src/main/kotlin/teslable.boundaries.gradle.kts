import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ExternalModuleDependency

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

/** :domain 이 절대 가져오면 안 되는 외부 그룹 */
val forbiddenGroupsForDomain = setOf("com.juul.kable", "dev.whyoleg.cryptography", "co.touchlab.skie")

gradle.projectsEvaluated {
    rootProject.subprojects.forEach { sub ->
        val permitted = allowedProjectDependencies[sub.path] ?: return@forEach
        sub.configurations.configureEach {
            val isTestConfiguration = name.contains("Test", ignoreCase = true)
            dependencies.withType<ProjectDependency>().configureEach {
                val target = path
                val ok = target == sub.path || target in permitted || (isTestConfiguration && target in testOnlyProjects)
                if (!ok) {
                    throw GradleException(
                        "Architecture boundary violation: ${sub.path} ($name) -> $target. " +
                            "Allowed: $permitted (+ :testing in test configurations). See ADR-0001 / docs/workflow.md §4.2",
                    )
                }
            }
            if (sub.path == ":domain" && !isTestConfiguration) {
                dependencies.withType<ExternalModuleDependency>().configureEach {
                    if (group in forbiddenGroupsForDomain) {
                        throw GradleException(":domain must stay pure Kotlin. Forbidden dependency: $group:$name (ADR-0001)")
                    }
                }
            }
        }
    }
}
