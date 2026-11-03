package net.plumt.sociallogin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/** 시드를 고정한 무작위 입력으로 "예외 대신 크래시" 가 없는지 본다. 실패하면 시드와 반복 번호로 재현한다. */
class CoreFuzzTest {

    private val alphabet = "abcXYZ019_- \t\n가🙂!@".toList()

    private fun Random.string(maxLen: Int): String {
        val len = nextInt(0, maxLen + 1)
        return buildString { repeat(len) { append(alphabet[nextInt(alphabet.size)]) } }
    }

    @Test
    fun `생성자는 성공하거나 IllegalArgumentException 만 던진다`() {
        val random = Random(20261102)
        var accepted = 0
        repeat(5_000) { i ->
            val s = random.string(40)
            try {
                ProviderId(s); accepted++
            } catch (_: IllegalArgumentException) {
            }
            try {
                SignInOptions(if (random.nextBoolean()) null else random.string(600)); accepted++
            } catch (_: IllegalArgumentException) {
            }
            try {
                SocialToken(
                    accessToken = if (random.nextBoolean()) null else random.string(8),
                    idToken = if (random.nextBoolean()) null else random.string(8),
                    refreshToken = if (random.nextBoolean()) null else random.string(8),
                    accessTokenExpiresAtMillis = if (random.nextBoolean()) null else random.nextLong(),
                    refreshTokenExpiresAtMillis = if (random.nextBoolean()) null else random.nextLong(),
                    scopes = List(random.nextInt(3)) { random.string(4) }.toSet(),
                ).toString(); accepted++
            } catch (_: IllegalArgumentException) {
            } catch (e: Throwable) {
                throw AssertionError("반복 $i 에서 예상 밖 예외", e)
            }
        }
        assertTrue(accepted > 0)
    }

    @Test
    fun `분류는 어떤 예외 사슬에서도 끝난다`() {
        val random = Random(7)
        val leaves = listOf({ IOException() }, { IllegalStateException() }, { RuntimeException() }, { Exception() })
        repeat(2_000) {
            var t: Throwable = leaves[random.nextInt(leaves.size)]()
            repeat(random.nextInt(0, 40)) { t = RuntimeException(t) }
            assertNotNull(SocialLoginErrors.classify(t))
        }
    }

    @Test
    fun `무작위 결과·예외를 내는 Provider 앞에서도 Manager 는 값만 돌려준다`() = runTest {
        val random = Random(42)
        val provider = FakeProvider(ProviderId.KAKAO)
        val outcomes: List<() -> SocialLoginResult<Nothing>> = listOf(
            { SocialLoginResult.Cancelled },
            { SocialLoginResult.Failure(FailureKind.entries[random.nextInt(FailureKind.entries.size)], "m") },
            { throw IOException() },
            { throw IllegalArgumentException() },
            { throw UnsupportedOperationException() },
        )
        fun next() = outcomes[random.nextInt(outcomes.size)]()
        provider.signInResult = { _, _ -> next() }
        provider.signOutResult = { next() }
        provider.unlinkResult = { next() }
        provider.refreshResult = { next() }
        val m = SocialLoginManager.builder<String>().register(provider).build()
        val ids = listOf(ProviderId.KAKAO, ProviderId.NAVER)
        repeat(3_000) { i ->
            val id = ids[random.nextInt(ids.size)]
            val result = try {
                when (random.nextInt(4)) {
                    0 -> m.signIn(id, "h")
                    1 -> m.signOut(id)
                    2 -> m.unlink(id)
                    else -> m.refreshToken(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw AssertionError("반복 $i 에서 Manager 가 예외를 냈다", e)
            }
            if (result is SocialLoginResult.Failure) assertEquals(id, result.providerId)
        }
    }
}
