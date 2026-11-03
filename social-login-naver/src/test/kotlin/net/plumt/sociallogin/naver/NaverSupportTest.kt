package net.plumt.sociallogin.naver

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.navercorp.nid.oauth.NidOAuthErrorCode
import com.navercorp.nid.oauth.NidOAuthErrorCode.ACTIVITY_IS_SINGLE_TASK
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_ERROR_CERTIFICATION_ERROR
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_ERROR_CONNECTION_ERROR
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_ERROR_NO_CALLBACKURL
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_ERROR_NO_CLIENTID
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_ERROR_NO_CLIENTNAME
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_ERROR_NO_CLIENTSECRET
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_ERROR_PARSING_FAIL
import com.navercorp.nid.oauth.NidOAuthErrorCode.CLIENT_USER_CANCEL
import com.navercorp.nid.oauth.NidOAuthErrorCode.ERROR_NO_CATAGORIZED
import com.navercorp.nid.oauth.NidOAuthErrorCode.NONE
import com.navercorp.nid.oauth.NidOAuthErrorCode.NO_APP_FOR_AUTHENTICATION
import com.navercorp.nid.oauth.NidOAuthErrorCode.SDK_IS_NOT_INITIALIZED
import com.navercorp.nid.oauth.NidOAuthErrorCode.SERVER_ERROR_ACCESS_DENIED
import com.navercorp.nid.oauth.NidOAuthErrorCode.SERVER_ERROR_INVALID_REQUEST
import com.navercorp.nid.oauth.NidOAuthErrorCode.SERVER_ERROR_INVALID_SCOPE
import com.navercorp.nid.oauth.NidOAuthErrorCode.SERVER_ERROR_SERVER_ERROR
import com.navercorp.nid.oauth.NidOAuthErrorCode.SERVER_ERROR_TEMPORARILY_UNAVAILABLE
import com.navercorp.nid.oauth.NidOAuthErrorCode.SERVER_ERROR_UNAUTHORIZED_CLIENT
import com.navercorp.nid.oauth.NidOAuthErrorCode.SERVER_ERROR_UNSUPPORTED_RESPONSE_TYPE
import com.navercorp.nid.oauth.NidOAuthErrorCode.WEB_VIEW_IS_DEPRECATED
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.FailureKind.ACCESS_DENIED
import net.plumt.sociallogin.FailureKind.CONFIGURATION
import net.plumt.sociallogin.FailureKind.NETWORK
import net.plumt.sociallogin.FailureKind.PROVIDER_SERVER
import net.plumt.sociallogin.FailureKind.UNKNOWN
import net.plumt.sociallogin.FailureKind.UNSUPPORTED
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.naver.internal.InitGuard
import net.plumt.sociallogin.naver.internal.NaverErrors
import net.plumt.sociallogin.naver.internal.NaverSdkException
import net.plumt.sociallogin.naver.internal.naverCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class NaverErrorsTest {

    private val table: Map<NidOAuthErrorCode, FailureKind> = mapOf(
        NONE to UNKNOWN,
        SERVER_ERROR_INVALID_REQUEST to CONFIGURATION,
        SERVER_ERROR_UNAUTHORIZED_CLIENT to CONFIGURATION,
        SERVER_ERROR_ACCESS_DENIED to ACCESS_DENIED,
        SERVER_ERROR_UNSUPPORTED_RESPONSE_TYPE to CONFIGURATION,
        SERVER_ERROR_INVALID_SCOPE to CONFIGURATION,
        SERVER_ERROR_SERVER_ERROR to PROVIDER_SERVER,
        SERVER_ERROR_TEMPORARILY_UNAVAILABLE to PROVIDER_SERVER,
        ERROR_NO_CATAGORIZED to UNKNOWN,
        CLIENT_ERROR_PARSING_FAIL to UNKNOWN,
        CLIENT_ERROR_NO_CLIENTID to CONFIGURATION,
        CLIENT_ERROR_NO_CLIENTSECRET to CONFIGURATION,
        CLIENT_ERROR_NO_CLIENTNAME to CONFIGURATION,
        CLIENT_ERROR_NO_CALLBACKURL to CONFIGURATION,
        CLIENT_ERROR_CONNECTION_ERROR to NETWORK,
        CLIENT_ERROR_CERTIFICATION_ERROR to NETWORK,
        CLIENT_USER_CANCEL to UNKNOWN,
        ACTIVITY_IS_SINGLE_TASK to CONFIGURATION,
        WEB_VIEW_IS_DEPRECATED to UNSUPPORTED,
        NO_APP_FOR_AUTHENTICATION to UNSUPPORTED,
        SDK_IS_NOT_INITIALIZED to CONFIGURATION,
    )

    @Test
    fun `표가 SDK 의 모든 오류 코드를 덮는다`() {
        assertEquals(NidOAuthErrorCode.entries.toSet(), table.keys)
    }

    @Test
    fun `오류 코드별 분류 — HTTP 상태가 없을 때`() {
        table.forEach { (code, kind) -> assertEquals(code.name, kind, NaverErrors.kindOf(code, null)) }
    }

    @Test
    fun `오류 코드가 있으면 HTTP 상태와 무관하다`() {
        table.keys.filter { it != NONE && it != ERROR_NO_CATAGORIZED }.forEach { code ->
            listOf(200, 401, 500).forEach { status -> assertEquals(table[code], NaverErrors.kindOf(code, status)) }
        }
    }

    @Test
    fun `코드 없는 실패는 HTTP 상태로 가른다`() {
        listOf(NONE, ERROR_NO_CATAGORIZED).forEach { code ->
            assertEquals(UNKNOWN, NaverErrors.kindOf(code, null))
            assertEquals(FailureKind.AUTHENTICATION, NaverErrors.kindOf(code, 401))
            assertEquals(PROVIDER_SERVER, NaverErrors.kindOf(code, 500))
            assertEquals(PROVIDER_SERVER, NaverErrors.kindOf(code, 599))
            assertEquals(UNKNOWN, NaverErrors.kindOf(code, 499))
            assertEquals(UNKNOWN, NaverErrors.kindOf(code, 600))
            assertEquals(UNKNOWN, NaverErrors.kindOf(code, -1))
        }
    }

    @Test
    fun `toResult — 취소, 코드, 원인 보존, 오류 번호 표기`() {
        assertSame(SocialLoginResult.Cancelled, NaverErrors.toResult(NaverSdkException(CLIENT_USER_CANCEL, null, 1), "signIn"))
        NidOAuthErrorCode.entries.filter { it != CLIENT_USER_CANCEL }.forEach { code ->
            val e = NaverSdkException(code, null, 7)
            val f = NaverErrors.toResult(e, "signIn") as SocialLoginResult.Failure
            assertEquals(table[code], f.kind)
            assertEquals("${code.name}(error 7)", f.vendorCode)
            assertEquals("네이버 signIn 실패: ${code.name}(error 7)", f.message)
            assertEquals(ProviderId.NAVER, f.providerId)
            assertSame(e, f.cause)
        }
        val noNumbers = NaverErrors.toResult(NaverSdkException(NONE, null, null), "x") as SocialLoginResult.Failure
        assertEquals("NONE", noNumbers.vendorCode)
    }

    @Test
    fun `SDK 밖 예외는 공통 분류`() {
        val f = NaverErrors.toResult(IOException(), "refreshToken") as SocialLoginResult.Failure
        assertEquals(NETWORK, f.kind)
        assertEquals(null, f.vendorCode)
        assertTrue(f.message.contains("java.io.IOException"))
        assertEquals(UNKNOWN, (NaverErrors.toResult(IllegalStateException(), "x") as SocialLoginResult.Failure).kind)
    }

    @Test
    fun `예외 메시지에 콜백 문구가 들어가지 않는다`() {
        assertEquals("네이버 SDK 실패: NONE (HTTP 500, error null)", NaverSdkException(NONE, 500, null).message)
    }

    @Test
    fun `무작위 오류에도 분류는 값만 돌려준다`() {
        val random = Random(5)
        repeat(3_000) {
            val e = NaverSdkException(
                NidOAuthErrorCode.entries.random(random),
                if (random.nextBoolean()) null else random.nextInt(-1000, 1000),
                if (random.nextBoolean()) null else random.nextInt(),
            )
            val r = NaverErrors.toResult(e, "op")
            assertTrue(r is SocialLoginResult.Failure || r === SocialLoginResult.Cancelled)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class NaverCallbacksTest {

    @Test
    fun `onSuccess 는 재개하고 늦은 콜백은 버린다`() = runTest {
        var lastCodeReads = 0
        suspendCancellableCoroutine { cont ->
            val cb = naverCallback(cont) { lastCodeReads++; NONE }
            cb.onSuccess()
            cb.onFailure(500, "late")
            cb.onError(1, "late")
            cb.onSuccess()
        }
        assertEquals(0, lastCodeReads)
    }

    @Test
    fun `onFailure 는 HTTP 상태와 마지막 오류 코드로 재개한다`() = runTest {
        val e = runCatching {
            suspendCancellableCoroutine<Unit> { cont ->
                naverCallback(cont) { SERVER_ERROR_SERVER_ERROR }.onFailure(503, "서버 문구")
            }
        }.exceptionOrNull() as NaverSdkException
        assertEquals(SERVER_ERROR_SERVER_ERROR, e.code)
        assertEquals(503, e.httpStatus)
        assertEquals(null, e.callbackErrorCode)
        assertFalse(e.message!!.contains("서버 문구"))
    }

    @Test
    fun `onError 는 오류 번호와 마지막 오류 코드로 재개한다`() = runTest {
        val e = runCatching {
            suspendCancellableCoroutine<Unit> { cont ->
                naverCallback(cont) { CLIENT_USER_CANCEL }.onError(-1, "user_cancel")
            }
        }.exceptionOrNull() as NaverSdkException
        assertEquals(CLIENT_USER_CANCEL, e.code)
        assertEquals(null, e.httpStatus)
        assertEquals(-1, e.callbackErrorCode)
    }

    @Test
    fun `취소된 뒤 늦은 콜백은 버린다`() = runTest(StandardTestDispatcher()) {
        var captured: kotlinx.coroutines.CancellableContinuation<Unit>? = null
        val d = async { suspendCancellableCoroutine<Unit> { captured = it } }
        runCurrent()
        d.cancel()
        runCurrent()
        val cb = naverCallback(captured!!) { NONE }
        cb.onSuccess()
        cb.onFailure(1, "x")
        cb.onError(1, "x")
        assertTrue(d.isCancelled)
    }
}

@RunWith(RobolectricTestRunner::class)
class NaverLoginConfigTest {

    @Test
    fun `경계값`() {
        NaverLoginConfig("a", "b", "c")
        NaverLoginConfig("a".repeat(128), "b".repeat(128), "이름🙂".repeat(20))
        NaverLoginConfig("a", "b", "n".repeat(100))
        assertEquals(128, NaverLoginConfig.MAX_CREDENTIAL_LENGTH)
        assertEquals(100, NaverLoginConfig.MAX_NAME_LENGTH)
    }

    @Test
    fun `틀린 값은 길이만 알리고 거절`() {
        listOf("", " a", "a b", "a".repeat(129)).forEach { bad ->
            val e1 = assertThrows(IllegalArgumentException::class.java) { NaverLoginConfig(bad, "s", "n") }
            assertTrue(e1.message!!.startsWith("clientId"))
            assertTrue(e1.message!!.contains("받은 길이 ${bad.length}"))
            val e2 = assertThrows(IllegalArgumentException::class.java) { NaverLoginConfig("i", bad, "n") }
            assertTrue(e2.message!!.startsWith("clientSecret"))
            if (bad.isNotBlank()) assertFalse(e2.message!!.contains(bad))
        }
        listOf("", " ", "n".repeat(101)).forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) { NaverLoginConfig("i", "s", bad) }
            assertTrue(e.message!!.startsWith("clientName"))
        }
    }

    @Test
    fun `toString 은 ID·Secret 을 가린다, equals 는 세 필드를 본다`() {
        val c = NaverLoginConfig("myclientid", "mysecretvalue", "앱 이름")
        assertFalse(c.toString().contains("myclientid"))
        assertFalse(c.toString().contains("mysecretvalue"))
        assertTrue(c.toString().contains("앱 이름"))
        assertEquals(c, NaverLoginConfig("myclientid", "mysecretvalue", "앱 이름"))
        assertEquals(c.hashCode(), NaverLoginConfig("myclientid", "mysecretvalue", "앱 이름").hashCode())
        assertNotEquals(c, NaverLoginConfig("x", "mysecretvalue", "앱 이름"))
        assertNotEquals(c, NaverLoginConfig("myclientid", "x", "앱 이름"))
        assertNotEquals(c, NaverLoginConfig("myclientid", "mysecretvalue", "x"))
        assertNotEquals(c as Any, "x")
    }

    @Test
    fun `무작위 값은 성공하거나 IllegalArgumentException 만`() {
        val random = Random(11)
        val chars = "aZ09 _-가\t".toList()
        fun s() = buildString { repeat(random.nextInt(0, 140)) { append(chars[random.nextInt(chars.size)]) } }
        repeat(3_000) {
            try {
                NaverLoginConfig(s(), s(), s())
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 35])
class NaverLoginFactoryTest {

    @Test
    fun `팩토리는 SDK 를 건드리지 않고 Provider 를 만든다`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val p = NaverLogin.provider(app, NaverLoginConfig("i", "s", "n"))
        assertEquals(ProviderId.NAVER, p.id)
        assertSame(ProviderId.NAVER, NaverLogin.PROVIDER_ID)
        assertEquals(setOf(Capability.SIGN_OUT, Capability.UNLINK, Capability.REFRESH_TOKEN), p.capabilities)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertEquals(ProviderId.NAVER, NaverLogin.provider(activity, NaverLoginConfig("i", "s", "n")).id)
    }
}

@RunWith(RobolectricTestRunner::class)
class NaverInitGuardTest {

    @Test
    fun `한 번만 초기화, 다른 키는 거절, 실패는 재시도`() {
        val guard = InitGuard("네이버 SDK")
        var count = 0
        assertThrows(IllegalStateException::class.java) { guard.ensure("k") { throw IllegalStateException() } }
        guard.ensure("k") { count++ }
        guard.ensure("k") { count++ }
        assertEquals(1, count)
        val e = assertThrows(IllegalStateException::class.java) { guard.ensure("k2") {} }
        assertTrue(e.message!!.startsWith("네이버 SDK"))
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
