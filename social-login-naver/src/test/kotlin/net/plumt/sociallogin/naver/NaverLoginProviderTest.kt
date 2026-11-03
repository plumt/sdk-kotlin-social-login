package net.plumt.sociallogin.naver

import android.app.Activity
import com.navercorp.nid.oauth.NidOAuthErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialAccount
import net.plumt.sociallogin.SocialLoginManager
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.SocialToken
import net.plumt.sociallogin.naver.internal.NaverGateway
import net.plumt.sociallogin.naver.internal.NaverLoginProvider
import net.plumt.sociallogin.naver.internal.NaverSdkException
import net.plumt.sociallogin.naver.internal.NaverStoredToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** 기기에 저장된 토큰 흉내 — 프로세스가 다시 떠도 남는 저장소. */
internal class FakeNaverStore {
    var access: String? = null
    var refresh: String? = null
    var expiresAtSeconds: Long = 0
}

internal class FakeNaverGateway(private val store: FakeNaverStore = FakeNaverStore()) : NaverGateway {
    val calls = mutableListOf<String>()
    var interceptor: CoroutineContext.Element? = null
    var initError: Exception? = null
    var authenticate: suspend () -> Unit = {
        store.access = "at"
        store.refresh = "rt"
        store.expiresAtSeconds = 1_700_000_000
    }
    var logout: () -> Unit = {
        store.access = null
        store.refresh = null
    }
    var deleteToken: suspend () -> Unit = {
        store.access = null
        store.refresh = null
    }
    var refresh: suspend () -> Unit = { store.access = store.access + "2" }

    override fun initialize(config: NaverLoginConfig) {
        calls += "init:${config.clientId}"
        initError?.let { throw it }
    }

    override suspend fun authenticate(activity: Activity) {
        calls += "authenticate"
        interceptor = currentCoroutineContext()[ContinuationInterceptor]
        authenticate.invoke()
    }

    override fun storedToken() = NaverStoredToken(store.access, store.refresh, store.expiresAtSeconds)

    override fun logout() {
        calls += "logout"
        logout.invoke()
    }

    override suspend fun deleteToken() {
        calls += "delete"
        deleteToken.invoke()
    }

    override suspend fun refreshAccessToken() {
        calls += "refresh"
        refresh.invoke()
    }
}

@RunWith(RobolectricTestRunner::class)
class NaverLoginProviderTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val store = FakeNaverStore()
    private val gateway = FakeNaverGateway(store)
    private val dispatcher = UnconfinedTestDispatcher()
    private val config = NaverLoginConfig("cid", "csecret", "앱")

    private fun provider(g: FakeNaverGateway = gateway) = NaverLoginProvider(config, g, dispatcher)

    private fun SocialLoginResult<*>.failure() = this as SocialLoginResult.Failure

    @Test
    fun `식별자와 지원 동작`() {
        assertEquals(ProviderId.NAVER, provider().id)
        assertEquals(setOf(Capability.SIGN_OUT, Capability.UNLINK, Capability.REFRESH_TOKEN), provider().capabilities)
    }

    @Test
    fun `로그인 성공 — 저장 토큰을 초→밀리초로 옮기고 ID 토큰은 없다`() = runTest(dispatcher) {
        val account = (provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Success).value
        assertEquals(
            SocialAccount(ProviderId.NAVER, SocialToken("at", null, "rt", 1_700_000_000_000L)),
            account,
        )
        assertEquals(listOf("init:cid", "authenticate"), gateway.calls)
        assertSame(dispatcher, gateway.interceptor)
    }

    @Test
    fun `nonce 를 넘기면 SDK 를 부르지 않고 UNSUPPORTED`() = runTest(dispatcher) {
        val f = provider().signIn(activity, SignInOptions("n")).failure()
        assertEquals(FailureKind.UNSUPPORTED, f.kind)
        assertTrue(f.message.contains("nonce"))
        assertEquals(ProviderId.NAVER, f.providerId)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `사용자 취소는 Cancelled`() = runTest(dispatcher) {
        gateway.authenticate = { throw NaverSdkException(NidOAuthErrorCode.CLIENT_USER_CANCEL, null, -1) }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
    }

    @Test
    fun `SDK 실패는 오류 코드로 분류한다`() = runTest(dispatcher) {
        val error = NaverSdkException(NidOAuthErrorCode.SERVER_ERROR_ACCESS_DENIED, 403, null)
        gateway.authenticate = { throw error }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.ACCESS_DENIED, f.kind)
        assertEquals("SERVER_ERROR_ACCESS_DENIED(HTTP 403)", f.vendorCode)
        assertSame(error, f.cause)
        gateway.authenticate = { throw IOException() }
        assertEquals(FailureKind.NETWORK, provider().signIn(activity, SignInOptions.DEFAULT).failure().kind)
    }

    @Test
    fun `성공 콜백 뒤 토큰이 비면 UNKNOWN`() = runTest(dispatcher) {
        gateway.authenticate = { store.access = " " }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertTrue(f.message.contains("signIn"))
    }

    @Test
    fun `초기화 실패는 CONFIGURATION 이고 다음 호출이 다시 시도한다`() = runTest(dispatcher) {
        gateway.initError = IllegalStateException("다른 ID")
        val p = provider()
        listOf(p.signIn(activity, SignInOptions.DEFAULT), p.signOut(), p.unlink(), p.refreshToken()).forEach {
            val f = it.failure()
            assertEquals(FailureKind.CONFIGURATION, f.kind)
            assertTrue(f.cause is IllegalStateException)
        }
        assertEquals(List(4) { "init:cid" }, gateway.calls)
        gateway.initError = null
        assertTrue(p.signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
    }

    @Test
    fun `로그아웃은 기기 토큰만 지운다, 실패는 분류한다`() = runTest(dispatcher) {
        val p = provider()
        p.signIn(activity, SignInOptions.DEFAULT)
        assertEquals(SocialLoginResult.Success(Unit), p.signOut())
        assertNull(store.access)
        gateway.logout = { throw IllegalStateException() }
        val f = p.signOut().failure()
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertTrue(f.message.contains("signOut"))
    }

    @Test
    fun `연결 해제 — 토큰이 없으면 NOT_SIGNED_IN 이고 서버를 부르지 않는다`() = runTest(dispatcher) {
        val p = provider()
        assertEquals(FailureKind.NOT_SIGNED_IN, p.unlink().failure().kind)
        assertTrue("delete" !in gateway.calls)
        store.access = null
        store.refresh = "rt"
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        store.access = "at"
        store.refresh = " "
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
    }

    @Test
    fun `연결 해제 실패 분류`() = runTest(dispatcher) {
        val p = provider()
        p.signIn(activity, SignInOptions.DEFAULT)
        gateway.deleteToken = { throw NaverSdkException(NidOAuthErrorCode.SERVER_ERROR_SERVER_ERROR, 500, null) }
        val f = p.unlink().failure()
        assertEquals(FailureKind.PROVIDER_SERVER, f.kind)
        assertTrue(f.message.contains("unlink"))
    }

    @Test
    fun `토큰 갱신 — 갱신 토큰이 없으면 NOT_SIGNED_IN, 있으면 새 토큰`() = runTest(dispatcher) {
        val p = provider()
        assertEquals(FailureKind.NOT_SIGNED_IN, p.refreshToken().failure().kind)
        p.signIn(activity, SignInOptions.DEFAULT)
        assertEquals("at2", (p.refreshToken() as SocialLoginResult.Success).value.accessToken)
        gateway.refresh = { throw NaverSdkException(NidOAuthErrorCode.NONE, 401, null) }
        val f = p.refreshToken().failure()
        assertEquals(FailureKind.AUTHENTICATION, f.kind)
        assertEquals("NONE(HTTP 401)", f.vendorCode)
        gateway.refresh = { store.access = null }
        assertEquals(FailureKind.UNKNOWN, p.refreshToken().failure().kind)
    }

    @Test
    fun `프로세스 재시작 — 새 Provider 가 저장된 토큰으로 갱신·해제한다`() = runTest(dispatcher) {
        provider(FakeNaverGateway(store)).signIn(activity, SignInOptions.DEFAULT)
        val restarted = provider(FakeNaverGateway(store))
        assertEquals("at2", (restarted.refreshToken() as SocialLoginResult.Success).value.accessToken)
        assertEquals(SocialLoginResult.Success(Unit), restarted.unlink())
        assertEquals(FailureKind.NOT_SIGNED_IN, provider(FakeNaverGateway(store)).unlink().failure().kind)
    }

    @Test
    fun `코루틴 취소는 결과로 바꾸지 않는다`() = runTest(dispatcher) {
        gateway.authenticate = { throw CancellationException("c") }
        try {
            provider().signIn(activity, SignInOptions.DEFAULT)
            fail()
        } catch (e: CancellationException) {
            assertEquals("c", e.message)
        }
        gateway.initError = CancellationException("init")
        try {
            provider().signOut()
            fail()
        } catch (e: CancellationException) {
            assertEquals("init", e.message)
        }
    }

    @Test
    fun `Manager 로 동시에 두 번 로그인하면 두 번째는 BUSY`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        gateway.authenticate = {
            gate.await()
            store.access = "at"
        }
        val manager = SocialLoginManager.builder<Activity>().register(provider()).build()
        val first = async { manager.signIn(ProviderId.NAVER, activity) }
        assertEquals(FailureKind.BUSY, manager.signIn(ProviderId.NAVER, activity).failure().kind)
        gate.complete(Unit)
        assertTrue(first.await() is SocialLoginResult.Success)
        assertEquals(1, gateway.calls.count { it == "authenticate" })
    }

    @Test
    fun `토큰 변환 경계값`() {
        assertNull(NaverLoginProvider.toSocialToken(NaverStoredToken(null, "r", 10)))
        assertNull(NaverLoginProvider.toSocialToken(NaverStoredToken("", "r", 10)))
        val zero = NaverLoginProvider.toSocialToken(NaverStoredToken("a", " ", 0))!!
        assertNull(zero.accessTokenExpiresAtMillis)
        assertNull(zero.refreshToken)
        assertNull(NaverLoginProvider.toSocialToken(NaverStoredToken("a", null, -1))!!.accessTokenExpiresAtMillis)
        assertEquals(1_000L, NaverLoginProvider.toSocialToken(NaverStoredToken("a", null, 1))!!.accessTokenExpiresAtMillis)
        // 밀리초로 바꾸면 넘치는 값은 "모름"
        assertNull(NaverLoginProvider.toSocialToken(NaverStoredToken("a", null, Long.MAX_VALUE))!!.accessTokenExpiresAtMillis)
        val max = Long.MAX_VALUE / 1000
        assertEquals(max * 1000, NaverLoginProvider.toSocialToken(NaverStoredToken("a", null, max))!!.accessTokenExpiresAtMillis)
    }
}
