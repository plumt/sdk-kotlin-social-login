package net.plumt.sociallogin.kakao

import android.app.Activity
import com.kakao.sdk.common.model.AuthError
import com.kakao.sdk.common.model.AuthErrorCause
import com.kakao.sdk.common.model.AuthErrorResponse
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialAccount
import net.plumt.sociallogin.SocialLoginConfig
import net.plumt.sociallogin.SocialLoginManager
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.SocialToken
import net.plumt.sociallogin.kakao.internal.KakaoLoginProvider
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

@RunWith(RobolectricTestRunner::class)
class KakaoLoginProviderTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeKakaoGateway()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun provider(useKakaoTalk: Boolean = true, g: FakeKakaoGateway = gateway) =
        KakaoLoginProvider(kakaoConfig("abc123", useKakaoTalk), g, dispatcher)

    private fun SocialLoginResult<*>.failure() = this as SocialLoginResult.Failure

    @Test
    fun `식별자와 지원 동작`() {
        val p = provider()
        assertEquals(ProviderId.KAKAO, p.id)
        assertEquals(setOf(Capability.SIGN_OUT, Capability.UNLINK, Capability.REFRESH_TOKEN), p.capabilities)
    }

    @Test
    fun `카카오톡 로그인 성공 — 웹을 부르지 않고 토큰을 옮긴다`() = runTest(dispatcher) {
        gateway.talk = { oauthToken("at", "rt", "idt", 1_000, 2_000, listOf("profile", " ", "account_email")) }
        val result = provider().signIn(activity, SignInOptions("n1"))
        val account = (result as SocialLoginResult.Success).value
        assertEquals(
            SocialAccount(ProviderId.KAKAO, SocialToken("at", "idt", "rt", 1_000, 2_000, setOf("profile", "account_email"))),
            account,
        )
        assertNull(account.email)
        assertNull(account.displayName)
        assertEquals(listOf("init:abc123", "available", "talk"), gateway.calls)
        assertEquals(listOf<String?>("n1"), gateway.nonces)
    }

    @Test
    fun `로그인은 주입한 메인 디스패처에서 SDK 를 부른다`() = runTest(dispatcher) {
        provider().signIn(activity, SignInOptions.DEFAULT)
        assertSame(dispatcher, gateway.interceptor)
    }

    @Test
    fun `카카오톡이 없으면 바로 웹 로그인`() = runTest(dispatcher) {
        gateway.talkAvailable = { false }
        val account = (provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Success).value
        assertEquals("web", account.token.accessToken)
        assertEquals(listOf("init:abc123", "available", "web"), gateway.calls)
        assertEquals(listOf<String?>(null), gateway.nonces)
    }

    @Test
    fun `카카오톡을 쓰지 않는 설정이면 가용성도 묻지 않는다`() = runTest(dispatcher) {
        provider(useKakaoTalk = false).signIn(activity, SignInOptions("n"))
        assertEquals(listOf("init:abc123", "web"), gateway.calls)
        assertEquals(listOf<String?>("n"), gateway.nonces)
    }

    @Test
    fun `카카오톡에서 사용자가 취소하면 웹으로 넘어가지 않는다`() = runTest(dispatcher) {
        gateway.talk = { throw ClientError(ClientErrorCause.Cancelled, "cancel") }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
        assertEquals(listOf("init:abc123", "available", "talk"), gateway.calls)
    }

    @Test
    fun `카카오톡 동의 화면에서 취소하면 웹으로 넘어가지 않고 Cancelled`() = runTest(dispatcher) {
        gateway.talk = { throw AuthError(400, AuthErrorCause.AccessDenied, AuthErrorResponse("access_denied", null)) }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
        assertEquals(listOf("init:abc123", "available", "talk"), gateway.calls)
    }

    @Test
    fun `카카오톡 로그인이 취소 말고 실패하면 웹으로 넘어가고 nonce 를 그대로 쓴다`() = runTest(dispatcher) {
        gateway.talk = { throw AuthError(400, AuthErrorCause.InvalidRequest, AuthErrorResponse("e", null)) }
        // nonce 를 넘기면 ID 토큰이 와야 성공이다(0.0.2, OIDC)
        gateway.web = { oauthToken(access = "web", idToken = "idt") }
        val account = (provider().signIn(activity, SignInOptions("n2")) as SocialLoginResult.Success).value
        assertEquals("web", account.token.accessToken)
        assertEquals(listOf("init:abc123", "available", "talk", "web"), gateway.calls)
        assertEquals(listOf<String?>("n2", "n2"), gateway.nonces)
    }

    @Test
    fun `웹까지 실패하면 웹 실패로 분류하고 카카오톡 원인을 suppressed 로 남긴다`() = runTest(dispatcher) {
        val talkError = IllegalStateException("talk")
        val webError = AuthError(400, AuthErrorCause.Misconfigured, AuthErrorResponse("e", null))
        gateway.talk = { throw talkError }
        gateway.web = { throw webError }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.CONFIGURATION, f.kind)
        assertSame(webError, f.cause)
        assertEquals(listOf<Throwable>(talkError), webError.suppressed.toList())
    }

    @Test
    fun `카카오톡과 웹이 같은 예외를 던지면 자기 자신을 suppressed 로 붙이지 않는다`() = runTest(dispatcher) {
        val same = IOException("same")
        gateway.talk = { throw same }
        gateway.web = { throw same }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.NETWORK, f.kind)
        assertEquals(0, same.suppressed.size)
    }

    @Test
    fun `카카오톡 실패 뒤 웹에서 취소하면 Cancelled`() = runTest(dispatcher) {
        gateway.talk = { throw IOException() }
        gateway.web = { throw ClientError(ClientErrorCause.Cancelled, "c") }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
    }

    @Test
    fun `웹 로그인만 쓸 때의 실패 분류`() = runTest(dispatcher) {
        gateway.talkAvailable = { false }
        gateway.web = { throw IOException("net") }
        assertEquals(FailureKind.NETWORK, provider().signIn(activity, SignInOptions.DEFAULT).failure().kind)
    }

    @Test
    fun `가용성 확인이 던져도 결과로 바뀐다`() = runTest(dispatcher) {
        gateway.talkAvailable = { throw SecurityException("pm") }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertTrue(f.cause is SecurityException)
    }

    @Test
    fun `빈 액세스 토큰은 UNKNOWN`() = runTest(dispatcher) {
        gateway.talk = { oauthToken(access = " ") }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertTrue(f.message.contains("signIn"))
        gateway.refresh = { oauthToken(access = "") }
        assertEquals(FailureKind.UNKNOWN, provider().refreshToken().failure().kind)
    }

    @Test
    fun `초기화 실패는 CONFIGURATION 이고 SDK 를 부르지 않으며 다음 호출에 다시 시도한다`() = runTest(dispatcher) {
        gateway.initError = IllegalStateException("다른 키")
        val p = provider()
        listOf(
            p.signIn(activity, SignInOptions.DEFAULT),
            p.signOut(),
            p.unlink(),
            p.refreshToken(),
        ).forEach {
            val f = it.failure()
            assertEquals(FailureKind.CONFIGURATION, f.kind)
            assertTrue(f.cause is IllegalStateException)
            assertTrue(f.message.contains("java.lang.IllegalStateException"))
        }
        assertEquals(List(4) { "init:abc123" }, gateway.calls)
        gateway.initError = null
        assertTrue(p.signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
    }

    @Test
    fun `로그아웃·연결 해제 성공과 실패`() = runTest(dispatcher) {
        val p = provider()
        assertEquals(SocialLoginResult.Success(Unit), p.signOut())
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        gateway.logout = { throw ClientError(ClientErrorCause.TokenNotFound, "none") }
        gateway.unlink = { throw IOException() }
        val so = p.signOut().failure()
        assertEquals(FailureKind.NOT_SIGNED_IN, so.kind)
        assertTrue(so.message.contains("signOut"))
        val un = p.unlink().failure()
        assertEquals(FailureKind.NETWORK, un.kind)
        assertTrue(un.message.contains("unlink"))
    }

    @Test
    fun `토큰 갱신 — 로그인 전이면 NOT_SIGNED_IN, 로그인 뒤면 새 토큰`() = runTest(dispatcher) {
        val p = provider()
        assertEquals(FailureKind.NOT_SIGNED_IN, p.refreshToken().failure().kind)
        p.signIn(activity, SignInOptions.DEFAULT)
        val token = (p.refreshToken() as SocialLoginResult.Success).value
        assertEquals("at2", token.accessToken)
        gateway.refresh = { throw AuthError(401, AuthErrorCause.InvalidGrant, AuthErrorResponse("invalid_grant", null)) }
        val f = p.refreshToken().failure()
        assertEquals(FailureKind.AUTHENTICATION, f.kind)
        assertEquals("AuthError.InvalidGrant(401)", f.vendorCode)
    }

    @Test
    fun `프로세스 재시작 — 새 Provider 가 저장된 토큰으로 갱신한다`() = runTest(dispatcher) {
        val store = FakeTokenStore()
        provider(g = FakeKakaoGateway(store)).signIn(activity, SignInOptions.DEFAULT)
        // 프로세스가 죽고 다시 떠 Provider·게이트웨이가 새로 만들어진다. 저장소만 남는다
        val restarted = provider(g = FakeKakaoGateway(store))
        assertEquals("at2", (restarted.refreshToken() as SocialLoginResult.Success).value.accessToken)
        assertEquals(SocialLoginResult.Success(Unit), restarted.signOut())
        assertEquals(FailureKind.NOT_SIGNED_IN, provider(g = FakeKakaoGateway(store)).refreshToken().failure().kind)
    }

    @Test
    fun `게이트웨이의 코루틴 취소는 결과로 바꾸지 않는다`() = runTest(dispatcher) {
        gateway.talk = { throw CancellationException("talk-cancel") }
        try {
            provider().signIn(activity, SignInOptions.DEFAULT)
            fail()
        } catch (e: CancellationException) {
            assertEquals("talk-cancel", e.message)
        }
        assertEquals(listOf("init:abc123", "available", "talk"), gateway.calls)

        gateway.talk = { throw IOException() }
        gateway.web = { throw CancellationException("web-cancel") }
        try {
            provider().signIn(activity, SignInOptions.DEFAULT)
            fail()
        } catch (e: CancellationException) {
            assertEquals("web-cancel", e.message)
        }
        gateway.logout = { throw CancellationException("x") }
        try {
            provider().signOut()
            fail()
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `초기화 중 코루틴 취소도 다시 던진다`() = runTest(dispatcher) {
        gateway.initError = CancellationException("init-cancel")
        try {
            provider().signOut()
            fail()
        } catch (e: CancellationException) {
            assertEquals("init-cancel", e.message)
        }
    }

    @Test
    fun `카카오톡 로그인을 기다리다 호출이 취소되면 웹 폴백을 하지 않는다`() = runTest(StandardTestDispatcher()) {
        val never = CompletableDeferred<Nothing>()
        gateway.talk = { never.await() }
        val p = KakaoLoginProvider(kakaoConfig("abc123"), gateway, StandardTestDispatcher(testScheduler))
        val job = launch { p.signIn(activity, SignInOptions.DEFAULT) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf("init:abc123", "available", "talk"), gateway.calls)
    }

    @Test
    fun `Manager 로 동시에 두 번 로그인하면 두 번째는 BUSY 이고 SDK 는 한 번만 불린다`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        gateway.talk = {
            gate.await()
            oauthToken()
        }
        val manager = SocialLoginManager.create(SocialLoginConfig.builder<Activity>().register(provider()).build())
        val first = async { manager.signIn(ProviderId.KAKAO, activity) }
        val second = manager.signIn(ProviderId.KAKAO, activity)
        assertEquals(FailureKind.BUSY, second.failure().kind)
        gate.complete(Unit)
        assertTrue(first.await() is SocialLoginResult.Success)
        assertEquals(1, gateway.calls.count { it == "talk" })
    }

    @Test
    fun `토큰 변환 — 빈 값은 없음으로, 음수 시각은 null 로`() {
        val t = KakaoLoginProvider.toSocialToken(oauthToken("a", " ", "", -5, -1, null))!!
        assertNull(t.refreshToken)
        assertNull(t.idToken)
        assertNull(t.accessTokenExpiresAtMillis)
        assertNull(t.refreshTokenExpiresAtMillis)
        assertEquals(emptySet<String>(), t.scopes)
        assertNull(KakaoLoginProvider.toSocialToken(oauthToken(access = "")))
        val full = KakaoLoginProvider.toSocialToken(oauthToken("a", "r", "i", 0, Long.MAX_VALUE, listOf("x")))!!
        assertEquals(0L, full.accessTokenExpiresAtMillis)
        assertEquals(Long.MAX_VALUE, full.refreshTokenExpiresAtMillis)
        assertEquals("r", full.refreshToken)
    }
}
