package net.plumt.sociallogin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class SocialLoginResultTest {

    @Test
    fun `Success 동등성과 toString`() {
        val s = SocialLoginResult.Success(1)
        assertEquals(1, s.value)
        assertEquals(s, SocialLoginResult.Success(1))
        assertEquals(s.hashCode(), SocialLoginResult.Success(1).hashCode())
        assertNotEquals(s, SocialLoginResult.Success(2))
        assertNotEquals(s as Any, 1)
        assertEquals("Success(1)", s.toString())
        assertEquals(SocialLoginResult.Success(Unit), SocialLoginResult.Success(Unit))
        assertEquals(0, SocialLoginResult.Success(null).hashCode())
    }

    @Test
    fun `Success 의 toString 은 값의 가림을 따른다`() {
        val s = SocialLoginResult.Success(SocialToken("secret-access", null)).toString()
        assertFalse(s.contains("secret-access"))
    }

    @Test
    fun `Cancelled 는 단일 인스턴스`() {
        assertSame(SocialLoginResult.Cancelled, SocialLoginResult.Cancelled)
        assertEquals("Cancelled", SocialLoginResult.Cancelled.toString())
    }

    @Test
    fun `Failure 기본값과 검증`() {
        val f = SocialLoginResult.Failure(FailureKind.NETWORK, "m")
        assertEquals(FailureKind.NETWORK, f.kind)
        assertEquals("m", f.message)
        assertNull(f.providerId)
        assertNull(f.vendorCode)
        assertNull(f.cause)
        val e1 = assertThrows(IllegalArgumentException::class.java) { SocialLoginResult.Failure(FailureKind.UNKNOWN, " ") }
        assertTrue(e1.message!!.contains("message"))
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            SocialLoginResult.Failure(FailureKind.UNKNOWN, "m", vendorCode = "", providerId = null, cause = null)
        }
        assertTrue(e2.message!!.contains("vendorCode"))
    }

    @Test
    fun `Failure withProviderId 는 나머지를 보존한다`() {
        val cause = IOException("x")
        val f = SocialLoginResult.Failure(FailureKind.NETWORK, "m", null, "V", cause)
        val g = f.withProviderId(ProviderId.KAKAO)
        assertEquals(ProviderId.KAKAO, g.providerId)
        assertEquals(FailureKind.NETWORK, g.kind)
        assertEquals("m", g.message)
        assertEquals("V", g.vendorCode)
        assertSame(cause, g.cause)
    }

    @Test
    fun `Failure equals·hashCode 는 모든 필드를 본다`() {
        val cause = IOException("x")
        val base = SocialLoginResult.Failure(FailureKind.NETWORK, "m", ProviderId.KAKAO, "V", cause)
        assertEquals(base, SocialLoginResult.Failure(FailureKind.NETWORK, "m", ProviderId.KAKAO, "V", cause))
        assertEquals(base.hashCode(), SocialLoginResult.Failure(FailureKind.NETWORK, "m", ProviderId.KAKAO, "V", cause).hashCode())
        assertEquals(base, base)
        assertNotEquals(base, SocialLoginResult.Failure(FailureKind.UNKNOWN, "m", ProviderId.KAKAO, "V", cause))
        assertNotEquals(base, SocialLoginResult.Failure(FailureKind.NETWORK, "n", ProviderId.KAKAO, "V", cause))
        assertNotEquals(base, SocialLoginResult.Failure(FailureKind.NETWORK, "m", ProviderId.NAVER, "V", cause))
        assertNotEquals(base, SocialLoginResult.Failure(FailureKind.NETWORK, "m", ProviderId.KAKAO, "W", cause))
        assertNotEquals(base, SocialLoginResult.Failure(FailureKind.NETWORK, "m", ProviderId.KAKAO, "V", IOException("x")))
        assertNotEquals(base as Any, "m")
        SocialLoginResult.Failure(FailureKind.NETWORK, "m").hashCode()
    }

    @Test
    fun `Failure toString 은 원인 메시지를 넣지 않는다`() {
        val s = SocialLoginResult.Failure(
            FailureKind.NETWORK, "m", ProviderId.KAKAO, "V", IOException("token=abc"),
        ).toString()
        assertFalse(s.contains("token=abc"))
        assertTrue(s.contains("java.io.IOException"))
        assertTrue(s.contains("providerId=kakao"))
        assertTrue(SocialLoginResult.Failure(FailureKind.NETWORK, "m").toString().contains("cause=null"))
    }

    @Test
    fun `getOrNull 과 map`() {
        assertEquals(1, SocialLoginResult.Success(1).getOrNull())
        assertNull(SocialLoginResult.Cancelled.getOrNull())
        val f = SocialLoginResult.Failure(FailureKind.UNKNOWN, "m")
        assertNull(f.getOrNull())
        assertEquals(SocialLoginResult.Success("2"), SocialLoginResult.Success(1).map { (it + 1).toString() })
        assertSame(SocialLoginResult.Cancelled, (SocialLoginResult.Cancelled as SocialLoginResult<Int>).map { it + 1 })
        assertSame(f, (f as SocialLoginResult<Int>).map { it + 1 })
    }

    @Test
    fun `FailureKind 재시도 가능 집합이 정확하다`() {
        val retryable = FailureKind.entries.filter { it.retryable }.toSet()
        assertEquals(
            setOf(FailureKind.NETWORK, FailureKind.PROVIDER_SERVER, FailureKind.INTERRUPTED, FailureKind.BUSY, FailureKind.TIMEOUT),
            retryable,
        )
        assertEquals(13, FailureKind.entries.size)
    }

    @Test
    fun `Capability 목록`() {
        assertEquals(listOf("SIGN_OUT", "UNLINK", "REFRESH_TOKEN"), Capability.entries.map { it.name })
        assertEquals(listOf("REJECT", "SERIALIZE"), ConcurrencyPolicy.entries.map { it.name })
    }
}

class SocialLoginErrorsTest {

    @Test
    fun `망 예외는 NETWORK`() {
        assertEquals(FailureKind.NETWORK, SocialLoginErrors.classify(IOException()))
        assertEquals(FailureKind.NETWORK, SocialLoginErrors.classify(UnknownHostException()))
        assertEquals(FailureKind.NETWORK, SocialLoginErrors.classify(SocketTimeoutException()))
        assertEquals(FailureKind.NETWORK, SocialLoginErrors.classify(TimeoutException()))
    }

    @Test
    fun `원인 사슬 안의 망 예외도 NETWORK`() {
        val wrapped = RuntimeException(IllegalStateException(IOException()))
        assertEquals(FailureKind.NETWORK, SocialLoginErrors.classify(wrapped))
    }

    @Test
    fun `원인 사슬은 최대 깊이까지만 본다`() {
        var deep: Throwable = IOException()
        repeat(SocialLoginErrors.MAX_CAUSE_DEPTH - 1) { deep = RuntimeException(deep) }
        // 깊이 16 안(0..15)에 있다
        assertEquals(FailureKind.NETWORK, SocialLoginErrors.classify(deep))
        deep = RuntimeException(deep)
        assertEquals(FailureKind.UNKNOWN, SocialLoginErrors.classify(deep))
    }

    @Test
    fun `순환 원인 사슬에서도 끝난다`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(FailureKind.UNKNOWN, SocialLoginErrors.classify(a))
    }

    @Test
    fun `그 밖은 UNKNOWN`() {
        assertEquals(FailureKind.UNKNOWN, SocialLoginErrors.classify(RuntimeException()))
        assertEquals(FailureKind.UNKNOWN, SocialLoginErrors.classify(IllegalStateException()))
        assertEquals(FailureKind.UNKNOWN, SocialLoginErrors.classify(OutOfMemoryError()))
    }

    @Test
    fun `코루틴 취소는 분류하지 않는다`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SocialLoginErrors.classify(CancellationException("c"))
        }
        assertTrue(e.message!!.contains("CancellationException"))
        assertThrows(IllegalArgumentException::class.java) {
            SocialLoginErrors.failureOf(CancellationException("c"), "m")
        }
    }

    @Test
    fun `failureOf 는 원인을 보존한다`() {
        val cause = IOException("x")
        val f = SocialLoginErrors.failureOf(cause, "m", ProviderId.NAVER, "V")
        assertEquals(FailureKind.NETWORK, f.kind)
        assertSame(cause, f.cause)
        assertEquals(ProviderId.NAVER, f.providerId)
        assertEquals("V", f.vendorCode)
        assertEquals("m", f.message)
        val g = SocialLoginErrors.failureOf(RuntimeException(), "m")
        assertEquals(FailureKind.UNKNOWN, g.kind)
        assertNull(g.providerId)
        assertNull(g.vendorCode)
        assertThrows(IllegalArgumentException::class.java) { SocialLoginErrors.failureOf(RuntimeException(), "") }
    }
}

class SocialLoginProviderDefaultsTest {

    private class SignInOnly : SocialLoginProvider<Any> {
        override val id: ProviderId = ProviderId("apple")
        override val capabilities: Set<Capability> = emptySet()
        override suspend fun signIn(host: Any, options: SignInOptions): SocialLoginResult<SocialAccount> =
            SocialLoginResult.Cancelled
    }

    @Test
    fun `기본 구현은 UNSUPPORTED`() = runTest {
        val p = SignInOnly()
        listOf(
            p.signOut() to Capability.SIGN_OUT,
            p.unlink() to Capability.UNLINK,
            p.refreshToken() to Capability.REFRESH_TOKEN,
        ).forEach { (result, cap) ->
            val f = result as SocialLoginResult.Failure
            assertEquals(FailureKind.UNSUPPORTED, f.kind)
            assertEquals(ProviderId("apple"), f.providerId)
            assertEquals("apple 는 ${cap.name} 를 지원하지 않는다", f.message)
        }
        assertSame(SocialLoginResult.Cancelled, p.signIn(Any(), SignInOptions.DEFAULT))
    }
}
