package net.plumt.sociallogin.kakao

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.kakao.internal.InitGuard
import net.plumt.sociallogin.kakao.internal.resumeIfActive
import net.plumt.sociallogin.kakao.internal.tokenCallbackResult
import net.plumt.sociallogin.kakao.internal.unitCallbackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class KakaoLoginConfigTest {

    @Test
    fun `기본값과 경계`() {
        val c = KakaoLoginConfig("a")
        assertTrue(c.useKakaoTalk)
        assertEquals("a", c.nativeAppKey)
        KakaoLoginConfig("A1".repeat(KakaoLoginConfig.MAX_KEY_LENGTH / 2))
        assertEquals(128, KakaoLoginConfig.MAX_KEY_LENGTH)
    }

    @Test
    fun `틀린 키는 길이만 알리고 거절`() {
        listOf("", " ", "abc def", "키값", "abc-123", "abc\n", "a".repeat(129), "YOUR_KAKAO_NATIVE_APP_KEY").forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) { KakaoLoginConfig(bad) }
            assertTrue(e.message!!.contains("받은 길이 ${bad.length}"))
            if (bad.isNotBlank()) assertFalse(e.message!!.contains(bad))
        }
    }

    @Test
    fun `toString 은 키를 가린다, equals 는 두 필드를 본다`() {
        val c = KakaoLoginConfig("secretkey0123", useKakaoTalk = false)
        assertFalse(c.toString().contains("secretkey0123"))
        assertEquals("KakaoLoginConfig(nativeAppKey=<redacted>, useKakaoTalk=false)", c.toString())
        assertEquals(c, KakaoLoginConfig("secretkey0123", false))
        assertEquals(c.hashCode(), KakaoLoginConfig("secretkey0123", false).hashCode())
        assertNotEquals(c, KakaoLoginConfig("secretkey0123", true))
        assertNotEquals(c, KakaoLoginConfig("otherkey", false))
        assertNotEquals(c as Any, "secretkey0123")
    }

    @Test
    fun `무작위 키는 성공하거나 IllegalArgumentException 만`() {
        val random = Random(99)
        val chars = "aZ09 -_가\n".toList()
        repeat(3_000) {
            val key = buildString { repeat(random.nextInt(0, 140)) { append(chars[random.nextInt(chars.size)]) } }
            try {
                KakaoLoginConfig(key)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 35])
class KakaoLoginFactoryTest {

    @Test
    fun `팩토리는 SDK 를 건드리지 않고 Provider 를 만든다`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val p = KakaoLogin.provider(app, KakaoLoginConfig("abc123"))
        assertEquals(ProviderId.KAKAO, p.id)
        assertSame(ProviderId.KAKAO, KakaoLogin.PROVIDER_ID)
        assertEquals(setOf(Capability.SIGN_OUT, Capability.UNLINK, Capability.REFRESH_TOKEN), p.capabilities)
    }

    @Test
    fun `Activity Context 를 넘겨도 된다`() {
        val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java).get()
        assertEquals(ProviderId.KAKAO, KakaoLogin.provider(activity, KakaoLoginConfig("abc123")).id)
    }
}

@RunWith(RobolectricTestRunner::class)
class InitGuardTest {

    @Test
    fun `처음 한 번만 초기화하고 같은 키는 다시 하지 않는다`() {
        val guard = InitGuard("SDK")
        var count = 0
        guard.ensure("k") { count++ }
        guard.ensure("k") { count++ }
        assertEquals(1, count)
    }

    @Test
    fun `다른 키는 IllegalStateException`() {
        val guard = InitGuard("카카오 SDK")
        guard.ensure("k1") {}
        val e = assertThrows(IllegalStateException::class.java) { guard.ensure("k2") {} }
        assertTrue(e.message!!.startsWith("카카오 SDK"))
        assertFalse(e.message!!.contains("k1"))
        assertFalse(e.message!!.contains("k2"))
    }

    @Test
    fun `초기화가 던지면 표시를 세우지 않아 다시 시도한다`() {
        val guard = InitGuard("SDK")
        assertThrows(IllegalArgumentException::class.java) { guard.ensure("k") { throw IllegalArgumentException() } }
        var count = 0
        guard.ensure("other") { count++ }
        assertEquals(1, count)
    }

    @Test
    fun `여러 스레드가 동시에 불러도 초기화는 한 번`() {
        val guard = InitGuard("SDK")
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(32)
        repeat(32) {
            pool.execute {
                start.await()
                guard.ensure("k") { count.incrementAndGet() }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, count.get())
    }
}

@RunWith(RobolectricTestRunner::class)
class KakaoCallbacksTest {

    @Test
    fun `토큰 콜백 해석`() {
        val token = oauthToken()
        assertSame(token, tokenCallbackResult(token, null).getOrThrow())
        val error = IllegalArgumentException()
        assertSame(error, tokenCallbackResult(token, error).exceptionOrNull())
        assertSame(error, tokenCallbackResult(null, error).exceptionOrNull())
        val empty = tokenCallbackResult(null, null).exceptionOrNull()
        assertTrue(empty is IllegalStateException)
        assertTrue(unitCallbackResult(null).isSuccess)
        assertSame(error, unitCallbackResult(error).exceptionOrNull())
    }

    @Test
    fun `기다리는 중이면 재개하고 두 번째 콜백은 버린다`() = runTest {
        var second = true
        val value = suspendCancellableCoroutine { cont ->
            assertTrue(cont.resumeIfActive(Result.success(1)))
            second = cont.resumeIfActive(Result.success(2))
        }
        assertEquals(1, value)
        assertFalse(second)
    }

    @Test
    fun `실패 결과는 예외로 재개한다`() = runTest {
        val e = IllegalStateException("x")
        val thrown = runCatching {
            suspendCancellableCoroutine<Int> { cont -> cont.resumeIfActive(Result.failure(e)) }
        }.exceptionOrNull()
        // 테스트(-ea)에서는 코루틴 스택 복구가 예외를 복제할 수 있어 타입·메시지로 본다
        assertTrue(thrown is IllegalStateException)
        assertEquals(e.message, thrown!!.message)
    }

    @Test
    fun `취소된 뒤 늦은 콜백은 버린다`() = runTest(StandardTestDispatcher()) {
        var captured: kotlinx.coroutines.CancellableContinuation<Int>? = null
        val deferred = async { suspendCancellableCoroutine<Int> { captured = it } }
        runCurrent()
        deferred.cancel()
        runCurrent()
        assertFalse(captured!!.resumeIfActive(Result.success(1)))
        assertTrue(runCatching { deferred.await() }.exceptionOrNull() is CancellationException)
    }
}
