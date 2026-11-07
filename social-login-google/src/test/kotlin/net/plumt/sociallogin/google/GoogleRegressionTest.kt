package net.plumt.sociallogin.google

import android.app.Activity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.google.internal.GoogleLoginProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** 0.0.2 에서 고친 결함의 재현 테스트(Google 설정). */
@RunWith(RobolectricTestRunner::class)
class GoogleRegressionTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeGoogleGateway()
    private val dispatcher = UnconfinedTestDispatcher()

    // H4 — 빈 웹 클라이언트 ID 는 생성 때 던지지 않고 호출이 CONFIGURATION 으로 끝난다
    @Test
    fun `빈 serverClientId 는 생성 때 던지지 않고 모든 동작이 SDK 를 부르지 않은 CONFIGURATION`() = runTest(dispatcher) {
        for (blank in listOf("", "   ")) {
            val config = GoogleLoginConfig(blank)
            assertFalse(config.isConfigured)
            val p = GoogleLoginProvider(config, gateway, dispatcher)
            for (r in listOf(p.signIn(activity, SignInOptions.DEFAULT), p.signOut(), p.unlink())) {
                val f = r as SocialLoginResult.Failure
                assertEquals(FailureKind.CONFIGURATION, f.kind)
                assertEquals(ProviderId.GOOGLE, f.providerId)
                assertTrue(f.message, f.message.contains("serverClientId"))
            }
        }
        assertTrue("SDK 를 부르면 안 된다: ${gateway.calls}", gateway.calls.isEmpty())
        assertTrue(GoogleLoginConfig(CLIENT_ID).isConfigured)
    }
}
