package net.plumt.sociallogin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 코드에서 파생되는 공개 문서 표가 코드와 어긋나면 실패한다. 항목(실패 갈래·동작·버전)을 더할 때 고치는 곳은 코드 한 곳이고,
 * 문서를 빠뜨리면 이 테스트가 알린다. 테스트 작업 디렉터리는 모듈 디렉터리다.
 */
class DocsConsistencyTest {

    private val root = File("..").canonicalFile
    private val readme = File(root, "README.md").readText()
    private val changelog = File(root, "CHANGELOG.md").readText()
    private val errors = File(root, "ERRORS.md").readText()

    private fun versionOf(module: String): String =
        Properties().apply { File(root, "$module/gradle.properties").inputStream().use(::load) }
            .getProperty("VERSION_NAME")

    /** CHANGELOG 의 `## <module>` 절 본문. */
    private fun changelogSection(module: String): String {
        val lines = changelog.lines()
        val start = lines.indexOf("## $module")
        assertTrue("CHANGELOG 에 '## $module' 절이 없다", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n")
    }

    @Test
    fun `ERRORS 가 모든 실패 갈래를 적는다`() {
        val missing = FailureKind.entries.map { it.name }.filterNot { errors.contains("`$it`") }
        assertEquals("ERRORS.md 에 없는 FailureKind", emptyList<String>(), missing)
    }

    @Test
    fun `README 가 모든 동작을 적는다`() {
        val missing = Capability.entries.map { it.name }.filterNot { readme.contains(it) }
        assertEquals("README.md 에 없는 Capability", emptyList<String>(), missing)
    }

    @Test
    fun `모듈 버전이 README 설치 줄과 CHANGELOG 에 있다`() {
        for (module in MODULES) {
            val version = versionOf(module)
            assertTrue(
                "CHANGELOG '## $module' 에 '### [$version]' 이 없다",
                changelogSection(module).contains("### [$version]"),
            )
            if (module != "social-login-core") {
                assertTrue(
                    "README 설치 줄에 net.plumt.sociallogin:$module:$version 이 없다",
                    readme.contains("\"net.plumt.sociallogin:$module:$version\""),
                )
            }
        }
    }

    @Test
    fun `CHANGELOG 소제목은 추가·바뀜·깨짐·고침 뿐이다`() {
        val headings = changelog.lines().filter { it.startsWith("#### ") }.map { it.removePrefix("#### ").trim() }.toSet()
        assertEquals("CHANGELOG 의 허용되지 않은 소제목", emptySet<String>(), headings - ALLOWED_HEADINGS)
    }

    private companion object {
        val MODULES = listOf("social-login-core", "social-login-kakao", "social-login-naver", "social-login-google")
        val ALLOWED_HEADINGS = setOf("추가", "바뀜", "깨짐", "고침")
    }
}
