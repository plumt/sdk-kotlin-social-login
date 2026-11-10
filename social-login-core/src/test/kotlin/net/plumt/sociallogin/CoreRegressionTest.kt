package net.plumt.sociallogin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 — 콜백이 오지 않는 Provider 가 Manager 를 영구히 BUSY 로 묶지 않는다. */
class CallTimeoutTest {

    private val kakao = FakeProvider(ProviderId.KAKAO)
    private val naver = FakeProvider(ProviderId.NAVER)

    private fun manager(timeout: Long? = null, policy: ConcurrencyPolicy = ConcurrencyPolicy.REJECT): SocialLoginManager<String> {
        val b = SocialLoginConfig.builder<String>().register(kakao).register(naver).concurrencyPolicy(policy)
        if (timeout != null) b.callTimeoutMillis(timeout)
        return SocialLoginManager.create(b.build())
    }

    @Test
    fun `상한 없이 콜백이 오지 않으면 다른 수단까지 BUSY 로 묶인다(0_0_1 동작 재현)`() = runTest(StandardTestDispatcher()) {
        kakao.signInResult = { _, _ -> awaitCancellation() }
        val m = manager()
        val hung = async { m.signIn(ProviderId.KAKAO, "host") }
        runCurrent()
        advanceTimeBy(3_600_000)
        assertEquals(FailureKind.BUSY, (m.signIn(ProviderId.NAVER, "host") as SocialLoginResult.Failure).kind)
        hung.cancel()
    }

    @Test
    fun `상한을 넘으면 TIMEOUT 으로 끝내고 잠금을 푼다`() = runTest(StandardTestDispatcher()) {
        var providerCancelled = false
        kakao.signInResult = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                providerCancelled = true
            }
        }
        val m = manager(timeout = 1_000)
        val hung = async { m.signIn(ProviderId.KAKAO, "host") }
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(hung.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        val f = hung.await() as SocialLoginResult.Failure
        assertEquals(FailureKind.TIMEOUT, f.kind)
        assertTrue(f.kind.retryable)
        assertEquals(ProviderId.KAKAO, f.providerId)
        assertTrue(f.message, f.message.contains("1000ms"))
        assertTrue("Provider 코루틴이 취소돼야 한다", providerCancelled)
        assertTrue(m.signIn(ProviderId.NAVER, "host") is SocialLoginResult.Success)
    }

    @Test
    fun `상한 안에 끝나면 결과 그대로, 모든 동작에 적용된다`() = runTest(StandardTestDispatcher()) {
        kakao.signOutResult = { delay(500); SocialLoginResult.Success(Unit) }
        kakao.unlinkResult = { delay(5_000); SocialLoginResult.Success(Unit) }
        kakao.refreshResult = { delay(5_000); SocialLoginResult.Success(SocialToken("b", null)) }
        val m = manager(timeout = 1_000)
        assertEquals(SocialLoginResult.Success(Unit), m.signOut(ProviderId.KAKAO))
        assertEquals(FailureKind.TIMEOUT, (m.unlink(ProviderId.KAKAO) as SocialLoginResult.Failure).kind)
        assertEquals(FailureKind.TIMEOUT, (m.refreshToken(ProviderId.KAKAO) as SocialLoginResult.Failure).kind)
    }

    @Test
    fun `SERIALIZE 에서 차례를 기다리는 시간은 상한에 넣지 않는다`() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        kakao.signInResult = { _, _ -> delay(900); SocialLoginResult.Success(SocialAccount(ProviderId.KAKAO, SocialToken("a", null))) }
        naver.signInResult = { _, _ -> gate.await(); delay(900); SocialLoginResult.Success(SocialAccount(ProviderId.NAVER, SocialToken("a", null))) }
        val m = manager(timeout = 1_000, policy = ConcurrencyPolicy.SERIALIZE)
        val first = async { m.signIn(ProviderId.KAKAO, "h") }
        val second = async { m.signIn(ProviderId.NAVER, "h") }
        gate.complete(Unit)
        assertTrue(first.await() is SocialLoginResult.Success)
        assertTrue("앞 호출 900ms + 자기 900ms 여도 자기 몫만 잰다", second.await() is SocialLoginResult.Success)
    }

    @Test
    fun `상한은 1 이상`() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                SocialLoginConfig.builder<String>().callTimeoutMillis(bad)
            }
            assertTrue(e.message!!.contains("받은 값 $bad"))
        }
        SocialLoginManager.create(SocialLoginConfig.builder<String>().callTimeoutMillis(1).callTimeoutMillis(Long.MAX_VALUE).build())
    }
}

/** M2 — 수단별 추가 선택 사항을 호출마다 넘긴다. */
class SignInOptionsExtensionTest {

    private class ModeA(val v: Int) : SignInOptions.Extension {
        override fun equals(other: Any?) = other is ModeA && other.v == v
        override fun hashCode() = v
    }

    private object ModeB : SignInOptions.Extension

    @Test
    fun `타입으로 꺼낸다, 없으면 null`() {
        val o = SignInOptions("n", listOf(ModeA(1), ModeB))
        assertEquals(ModeA(1), o.extension<ModeA>())
        assertSame(ModeB, o.extension(ModeB::class.java))
        assertEquals(listOf(ModeA(1), ModeB), o.extensions)
        assertNull(SignInOptions.DEFAULT.extension<ModeA>())
        assertEquals(emptyList<SignInOptions.Extension>(), SignInOptions("n").extensions)
    }

    @Test
    fun `같은 클래스를 두 번 넣으면 거절`() {
        val e = assertThrows(IllegalArgumentException::class.java) { SignInOptions(null, listOf(ModeA(1), ModeA(2))) }
        assertTrue(e.message!!.contains("ModeA"))
    }

    @Test
    fun `동등성은 nonce 와 확장을 본다, 입력 목록을 복사한다`() {
        val list = mutableListOf<SignInOptions.Extension>(ModeA(1))
        val o = SignInOptions("n", list)
        list += ModeB
        assertEquals(listOf(ModeA(1)), o.extensions)
        assertEquals(o, SignInOptions("n", listOf(ModeA(1))))
        assertEquals(o.hashCode(), SignInOptions("n", listOf(ModeA(1))).hashCode())
        assertNotEquals(o, SignInOptions("n", listOf(ModeA(2))))
        assertNotEquals(o, SignInOptions("n"))
        assertEquals(SignInOptions("n"), SignInOptions("n", emptyList()))
    }

    @Test
    fun `toString 은 nonce 를 가리고 확장은 클래스 이름만`() {
        assertEquals("SignInOptions(nonce=<redacted>)", SignInOptions("secret").toString())
        val s = SignInOptions("secret", listOf(ModeA(42))).toString()
        assertEquals("SignInOptions(nonce=<redacted>, extensions=[ModeA])", s)
        assertFalse(s.contains("secret"))
    }

    @Test
    fun `확장이 있어도 nonce 검사는 그대로`() {
        assertThrows(IllegalArgumentException::class.java) { SignInOptions("", listOf(ModeB)) }
        assertThrows(IllegalArgumentException::class.java) { SignInOptions("a b", listOf(ModeB)) }
    }
}
