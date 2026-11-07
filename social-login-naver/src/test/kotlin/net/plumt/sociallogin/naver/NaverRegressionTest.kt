package net.plumt.sociallogin.naver

import android.app.Activity
import com.navercorp.nid.oauth.NidOAuthErrorCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.naver.internal.NaverErrors
import net.plumt.sociallogin.naver.internal.NaverLoginProvider
import net.plumt.sociallogin.naver.internal.NaverSdkException
import net.plumt.sociallogin.naver.internal.naverCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** 0.0.2 에서 고친 결함의 재현 테스트. */
@RunWith(RobolectricTestRunner::class)
class NaverRegressionTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val store = FakeNaverStore()
    private val gateway = FakeNaverGateway(store)
    private val dispatcher = UnconfinedTestDispatcher()

    private fun provider(config: NaverLoginConfig = NaverLoginConfig("id", "secret", "앱")) =
        NaverLoginProvider(config, gateway, dispatcher)

    private fun signedIn() {
        store.access = "at"
        store.refresh = "rt"
        store.expiresAtSeconds = 1_700_000_000
    }

    /** 실제 SDK 처럼 서버 응답을 보기 전에 기기 토큰부터 지우고 실패한다. */
    private fun deleteFailsAfterClearing(error: Exception) {
        gateway.deleteToken = {
            store.access = null
            store.refresh = null
            store.expiresAtSeconds = 0
            throw error
        }
    }

    // H2 — 망 오류로 연결 해제가 실패하면 다시 시도할 수 있어야 한다(서버 연동이 남은 채 NOT_SIGNED_IN 금지)
    @Test
    fun `연결 해제가 망 오류로 실패하면 기기 토큰을 되살려 재시도가 서버를 다시 부른다`() = runTest(dispatcher) {
        signedIn()
        deleteFailsAfterClearing(NaverSdkException(NidOAuthErrorCode.CLIENT_ERROR_CONNECTION_ERROR, null, -1))
        val first = provider().unlink() as SocialLoginResult.Failure
        assertEquals(FailureKind.NETWORK, first.kind)
        assertTrue(first.kind.retryable)
        assertEquals("at", store.access)
        assertEquals("rt", store.refresh)
        assertEquals(1_700_000_000, store.expiresAtSeconds)

        gateway.deleteToken = { store.access = null; store.refresh = null }
        assertEquals(SocialLoginResult.Success(Unit), provider().unlink())
        assertEquals(2, gateway.calls.count { it == "delete" })
    }

    @Test
    fun `재시도해도 소용없는 실패는 SDK 동작대로 토큰을 되살리지 않는다`() = runTest(dispatcher) {
        signedIn()
        deleteFailsAfterClearing(NaverSdkException(NidOAuthErrorCode.SERVER_ERROR_INVALID_REQUEST, null, 1))
        val f = provider().unlink() as SocialLoginResult.Failure
        assertEquals(FailureKind.CONFIGURATION, f.kind)
        assertEquals(null, store.access)
        assertSame(SocialLoginResult.Failure::class.java, f.javaClass)
    }

    // M1 — onFailure(HTTP 4xx) 때 SDK 가 오류 코드를 갱신하지 않으면 지난 코드가 아니라 HTTP 상태로 분류한다
    @Test
    fun `onFailure 때 오류 코드가 그대로면 지난 코드 대신 HTTP 상태로 분류한다`() = runTest {
        var code = NidOAuthErrorCode.CLIENT_ERROR_CONNECTION_ERROR
        val e = runCatching {
            suspendCancellableCoroutine<Unit> { cont ->
                val cb = naverCallback(cont) { code }
                cb.onFailure(401, "unauthorized")
            }
        }.exceptionOrNull() as NaverSdkException
        val f = NaverErrors.toResult(e, "refreshToken") as SocialLoginResult.Failure
        assertEquals(FailureKind.AUTHENTICATION, f.kind)

        // 직전 사용자 취소가 남아 있어도 갱신 실패를 Cancelled 로 묻지 않는다
        code = NidOAuthErrorCode.CLIENT_USER_CANCEL
        val e2 = runCatching {
            suspendCancellableCoroutine<Unit> { cont -> naverCallback(cont) { code }.onFailure(400, "bad") }
        }.exceptionOrNull() as NaverSdkException
        assertTrue(NaverErrors.toResult(e2, "refreshToken") is SocialLoginResult.Failure)
    }

    @Test
    fun `onFailure 중에 SDK 가 오류 코드를 새로 쓰면 그 코드를 쓴다`() = runTest {
        var code = NidOAuthErrorCode.NONE
        val e = runCatching {
            suspendCancellableCoroutine<Unit> { cont ->
                val cb = naverCallback(cont) { code }
                code = NidOAuthErrorCode.SERVER_ERROR_TEMPORARILY_UNAVAILABLE
                cb.onFailure(503, "x")
            }
        }.exceptionOrNull() as NaverSdkException
        assertEquals(NidOAuthErrorCode.SERVER_ERROR_TEMPORARILY_UNAVAILABLE, e.code)
    }

    // H4 — 빈 값은 생성 때 던지지 않고 호출이 CONFIGURATION 으로 끝난다
    @Test
    fun `빈 Client ID·Secret·이름은 생성 때 던지지 않고 모든 동작이 CONFIGURATION`() = runTest(dispatcher) {
        val configs = listOf(
            NaverLoginConfig("", "", ""),
            NaverLoginConfig("id", " ", "앱"),
            NaverLoginConfig("", "secret", "앱"),
            NaverLoginConfig("id", "secret", "  "),
        )
        for (config in configs) {
            assertFalse(config.isConfigured)
            val p = provider(config)
            for (r in listOf(p.signIn(activity, SignInOptions.DEFAULT), p.signOut(), p.unlink(), p.refreshToken())) {
                val f = r as SocialLoginResult.Failure
                assertEquals(FailureKind.CONFIGURATION, f.kind)
                assertEquals(ProviderId.NAVER, f.providerId)
            }
        }
        assertTrue("SDK 를 건드리면 안 된다: ${gateway.calls}", gateway.calls.isEmpty())
        assertTrue(NaverLoginConfig("id", "secret", "앱").isConfigured)
    }

    // H3 — 모르는 오류 코드도 예외 없이 HTTP 상태로 분류한다
    @Test
    fun `표에 없는 오류 코드 이름은 HTTP 상태로 가른다`() {
        assertEquals(FailureKind.UNKNOWN, NaverErrors.kindOf("NEW_CODE_2030", null))
        assertEquals(FailureKind.AUTHENTICATION, NaverErrors.kindOf("NEW_CODE_2030", 401))
        assertEquals(FailureKind.PROVIDER_SERVER, NaverErrors.kindOf("NEW_CODE_2030", 502))
        assertEquals(FailureKind.UNKNOWN, NaverErrors.kindOf("NEW_CODE_2030", 404))
    }

    @Test
    fun `표가 이 SDK 버전의 모든 오류 코드를 덮는다`() {
        val names = NidOAuthErrorCode.entries.map { it.name }.toSet()
        assertEquals(names, NaverErrors.CODES.keys + NaverErrors.UNCATEGORIZED)
        assertTrue(NaverErrors.CODES.keys.none { it in NaverErrors.UNCATEGORIZED })
    }

    @Test
    fun `연결 해제 실패 뒤 토큰 복원이 안 되면 그 사실을 알리고 원인에 붙인다`() = runTest(dispatcher) {
        signedIn()
        val error = NaverSdkException(NidOAuthErrorCode.CLIENT_ERROR_CONNECTION_ERROR, null, -1)
        deleteFailsAfterClearing(error)
        val restoreError = IllegalStateException("no store")
        gateway.restore = { throw restoreError }
        val f = provider().unlink() as SocialLoginResult.Failure
        assertEquals(FailureKind.NETWORK, f.kind)
        assertTrue(f.message, f.message.contains("되돌리지 못했다"))
        assertSame(error, f.cause)
        assertEquals(listOf<Throwable>(restoreError), error.suppressed.toList())
        assertEquals(null, store.access)
    }

    @Test
    fun `SDK 가 실패해도 토큰을 지우지 않았으면 되돌리지 않는다`() = runTest(dispatcher) {
        signedIn()
        gateway.deleteToken = { throw NaverSdkException(NidOAuthErrorCode.SERVER_ERROR_SERVER_ERROR, 500, null) }
        val f = provider().unlink() as SocialLoginResult.Failure
        assertEquals(FailureKind.PROVIDER_SERVER, f.kind)
        assertFalse(gateway.calls.contains("restore"))
        assertEquals("at", store.access)
    }

    @Test
    fun `되돌린 결과는 원래 분류·vendorCode 를 유지하고 메시지로 알린다`() = runTest(dispatcher) {
        signedIn()
        deleteFailsAfterClearing(NaverSdkException(NidOAuthErrorCode.SERVER_ERROR_TEMPORARILY_UNAVAILABLE, 503, null))
        val f = provider().unlink() as SocialLoginResult.Failure
        assertEquals(FailureKind.PROVIDER_SERVER, f.kind)
        assertEquals("SERVER_ERROR_TEMPORARILY_UNAVAILABLE(HTTP 503)", f.vendorCode)
        assertTrue(f.message, f.message.contains("되돌렸다"))
        assertEquals(ProviderId.NAVER, f.providerId)
        assertTrue(gateway.calls.contains("restore"))
    }

    @Test
    fun `연결 해제 중 코루틴이 취소되면 되돌리지 않는다`() = runTest(dispatcher) {
        signedIn()
        gateway.deleteToken = {
            store.access = null
            store.refresh = null
            throw kotlinx.coroutines.CancellationException("left")
        }
        val e = runCatching { provider().unlink() }.exceptionOrNull()
        assertTrue(e is kotlinx.coroutines.CancellationException)
        assertFalse(gateway.calls.contains("restore"))
    }
}
