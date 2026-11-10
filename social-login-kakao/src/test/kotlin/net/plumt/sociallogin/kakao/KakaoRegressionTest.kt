package net.plumt.sociallogin.kakao

import android.app.Activity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialLoginResult
import com.kakao.sdk.common.model.ApiErrorCause
import com.kakao.sdk.common.model.AuthErrorCause
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import net.plumt.sociallogin.kakao.internal.KakaoErrors
import net.plumt.sociallogin.kakao.internal.KakaoLoginProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** 0.0.2 에서 고친 결함의 재현 테스트. */
@RunWith(RobolectricTestRunner::class)
class KakaoRegressionTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeKakaoGateway()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun provider(config: KakaoLoginConfig = kakaoConfig("abc123")) =
        KakaoLoginProvider(config, gateway, dispatcher)

    // H4 — 빈 키는 생성 때 던지지 않고, 호출이 SDK 를 부르지 않은 채 CONFIGURATION 으로 끝난다
    @Test
    fun `빈 키는 생성 때 던지지 않고 모든 동작이 SDK 를 부르지 않은 CONFIGURATION`() = runTest(dispatcher) {
        for (blank in listOf("", " ", "\t\n")) {
            val config = kakaoConfig(blank)
            assertFalse(config.isConfigured)
            val p = provider(config)
            val results = listOf(
                p.signIn(activity, SignInOptions.DEFAULT),
                p.signOut(),
                p.unlink(),
                p.refreshToken(),
            )
            for (r in results) {
                val f = r as SocialLoginResult.Failure
                assertEquals(FailureKind.CONFIGURATION, f.kind)
                assertEquals(ProviderId.KAKAO, f.providerId)
                assertTrue(f.message, f.message.contains("nativeAppKey"))
            }
        }
        assertTrue("SDK 초기화·호출이 없어야 한다: ${gateway.calls}", gateway.calls.isEmpty())
        assertTrue(kakaoConfig("abc123").isConfigured)
    }

    // M4 — nonce 를 넘겼는데 ID 토큰이 없으면(OIDC 미활성화) 성공으로 넘기지 않는다
    @Test
    fun `nonce 를 넘겼는데 ID 토큰이 없으면 CONFIGURATION`() = runTest(dispatcher) {
        gateway.talk = { oauthToken(idToken = null) }
        val f = provider().signIn(activity, SignInOptions("n-1")) as SocialLoginResult.Failure
        assertEquals(FailureKind.CONFIGURATION, f.kind)
        assertTrue(f.message, f.message.contains("OpenID Connect"))
        assertEquals(ProviderId.KAKAO, f.providerId)
    }

    @Test
    fun `nonce 를 넘기고 ID 토큰이 오면 성공, nonce 없이 ID 토큰이 없어도 성공`() = runTest(dispatcher) {
        gateway.talk = { oauthToken(idToken = "idt") }
        val ok = provider().signIn(activity, SignInOptions("n-1")) as SocialLoginResult.Success
        assertEquals("idt", ok.value.token.idToken)
        gateway.talk = { oauthToken(idToken = null) }
        assertTrue(provider().signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
    }

    // H3 — 소비 앱이 더 새 SDK 를 써서 모르는 원인이 와도 예외가 아니라 UNKNOWN
    @Test
    fun `표에 없는 원인 이름은 UNKNOWN 이고 예외가 나지 않는다`() {
        for (name in listOf("SomethingNew2030", "", "cancelled", "CANCELLED")) {
            assertEquals(FailureKind.UNKNOWN, KakaoErrors.kindOfClient(name))
            assertEquals(FailureKind.UNKNOWN, KakaoErrors.kindOfAuth(name))
            assertEquals(FailureKind.UNKNOWN, KakaoErrors.kindOfApi(name))
        }
    }

    @Test
    fun `표가 이 SDK 버전의 모든 원인을 덮고, 표에만 있는 이름도 없다`() {
        assertEquals(ClientErrorCause.entries.map { it.name }.toSet(), KakaoErrors.CLIENT.keys)
        assertEquals(AuthErrorCause.entries.map { it.name }.toSet(), KakaoErrors.AUTH.keys)
        assertEquals(ApiErrorCause.entries.map { it.name }.toSet(), KakaoErrors.API.keys)
        for (c in ClientErrorCause.entries) assertEquals(KakaoErrors.CLIENT.getValue(c.name), KakaoErrors.kindOf(c))
        for (c in AuthErrorCause.entries) assertEquals(KakaoErrors.AUTH.getValue(c.name), KakaoErrors.kindOf(c))
        for (c in ApiErrorCause.entries) assertEquals(KakaoErrors.API.getValue(c.name), KakaoErrors.kindOf(c))
    }

    @Test
    fun `사용자 취소는 이름으로 판정한다`() {
        assertTrue(KakaoErrors.isUserCancel(ClientError(ClientErrorCause.Cancelled, "x")))
        assertFalse(KakaoErrors.isUserCancel(ClientError(ClientErrorCause.Unknown, "x")))
        assertFalse(KakaoErrors.isUserCancel(IllegalStateException("Cancelled")))
    }
}
