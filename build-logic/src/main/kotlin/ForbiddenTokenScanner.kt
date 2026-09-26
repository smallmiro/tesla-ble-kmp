import java.io.File

/**
 * `forbiddenTokens` 태스크의 스캔 로직을 담는 순수 Kotlin 유틸리티.
 *
 * Configuration Cache 호환성 참고: 이 로직을 `teslable.kmp-library.gradle.kts`의
 * `doLast { }` 블록 안에 최상위 함수로 두면, Kotlin 스크립트의 최상위 선언은 스크립트
 * 클래스(암묵적으로 `Project`를 캡처하는 인스턴스)의 멤버로 컴파일되므로 태스크 액션이
 * "Gradle script object reference"를 캡처해 Configuration Cache 직렬화에 실패한다.
 * 이 파일처럼 별도의 평범한 `.kt` 파일(스크립트가 아닌 일반 클래스/오브젝트)로 분리하면
 * `Project`나 스크립트 인스턴스를 전혀 참조하지 않으므로 안전하게 직렬화된다.
 *
 * detekt's type-resolution-dependent rules (ForbiddenMethodCall, UnsafeCallOnNullableType) don't
 * fire on the plain `detekt` task wired into `check`, so enforce the same bans with a lightweight
 * lexical scan (NFR-006, ADR-0001, docs/workflow.md §4.4). Main source sets only; test sources are
 * exempt.
 */
object ForbiddenTokenScanner {

    private val FORBIDDEN_TOKENS = listOf("!!", "println(", "runBlocking", "GlobalScope")

    private const val BLOCK_OPEN = "/" + "*"
    private const val BLOCK_CLOSE = "*" + "/"

    /**
     * Kotlin 어휘를 최소한으로 스캔해 문자열/문자 리터럴/주석(중첩 block comment 포함) 내부를
     * 공백/개행으로 지우고 실제 코드만 남긴다. 개행은 그대로 유지해 라인 번호가 어긋나지 않게
     * 한다. 정규식 두 패스로는 문자열 안의 미완성 블록 주석 시작 토큰이 문자열 경계를 벗어나
     * 실제 코드를 집어삼킬 수 있어(false negative), 왼쪽에서 오른쪽으로 한 번에 스캔한다.
     * 문자열 템플릿(${…}) 내부는 문자열의 일부로 취급해 별도로 파싱하지 않는다(허용된 한계).
     */
    fun stripNonCode(text: String): String {
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

    /**
     * [files]에서 금지 토큰을 찾는다. [moduleDir]는 위반 메시지의 상대 경로 계산에만 쓰인다.
     * 두 인자 모두 평범한 값(List<File>, File)이라 Configuration Cache 상에서 안전하게
     * 캡처/직렬화된다.
     */
    fun scan(files: List<File>, moduleDir: File): List<String> {
        val violations = mutableListOf<String>()
        files.forEach { file ->
            stripNonCode(file.readText()).lines().forEachIndexed { index, line ->
                FORBIDDEN_TOKENS.forEach { token ->
                    if (line.contains(token)) {
                        violations += "${file.relativeTo(moduleDir)}:${index + 1}: forbidden token '$token'"
                    }
                }
            }
        }
        return violations
    }
}
