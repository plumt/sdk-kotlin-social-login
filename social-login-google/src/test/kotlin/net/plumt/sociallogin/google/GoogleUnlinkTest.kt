package net.plumt.sociallogin.google

import android.app.Activity
import androidx.credentials.exceptions.ClearCredentialUnknownException
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.UnsupportedApiCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialAccount
import net.plumt.sociallogin.SocialLoginProvider
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.google.internal.GoogleErrors
import net.plumt.sociallogin.google.internal.GoogleLoginProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** 0.1.0 — Credential Manager 모드의 연결 해제(AuthorizationClient.revokeAccess)와 경로 선택. */
@RunWith(RobolectricTestRunner::class)
class GoogleUnlinkTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeGoogleGateway()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun provider(mode: GoogleSignInMode = GoogleSignInMode.BUTTON) =
        GoogleLoginProvider(googleConfig(CLIENT_ID, mode), gateway, dispatcher)

    private fun apiError(code: Int) = ApiException(Status(code))

    private fun SocialLoginResult<*>.failure() = this as SocialLoginResult.Failure

    private fun extension(mode: GoogleSignInMode) = SignInOptions(nonce = null, extensions = listOf(GoogleSignInExtension(mode)))

    private suspend fun GoogleLoginProvider.signedIn(): GoogleLoginProvider = apply {
        assertTrue(signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
        gateway.calls.clear()
    }

    @Test
    fun `Credential Manager 로그인 뒤 연결 해제 — 로그인한 계정의 권한을 철회하고 상태를 지운다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals(listOf("revokeAuthorization", "revoke", "clear"), gateway.calls)
        assertEquals(listOf("person@example.com"), gateway.revokedEmails)
    }

    @Test
    fun `시트 모드도 같은 경로로 철회한다`() = runTest(dispatcher) {
        val p = provider(GoogleSignInMode.BOTTOM_SHEET).signedIn()
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals("revokeAuthorization", gateway.calls.first())
    }

    @Test
    fun `철회에 성공하면 계정을 잊는다 — 다시 부르면 Google 을 부르지 않은 NOT_SIGNED_IN`() = runTest(dispatcher) {
        val p = provider().signedIn()
        p.unlink()
        gateway.calls.clear()
        val f = p.unlink().failure()
        assertEquals(FailureKind.NOT_SIGNED_IN, f.kind)
        assertEquals(GoogleLoginProvider.VENDOR_NO_ACCOUNT, f.vendorCode)
        assertEquals(ProviderId.GOOGLE, f.providerId)
        assertTrue(f.message.contains("GoogleLogin.unlink"))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `로그아웃도 계정을 잊는다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        assertEquals(SocialLoginResult.Success(Unit), p.signOut())
        assertEquals(FailureKind.NOT_SIGNED_IN, p.unlink().failure().kind)
        assertFalse(gateway.calls.contains("revokeAuthorization"))
    }

    @Test
    fun `로그아웃이 실패하면 계정을 기억한 채다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.clear = { throw ClearCredentialUnknownException("x") }
        assertTrue(p.signOut() is SocialLoginResult.Failure)
        gateway.clear = {}
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
    }

    @Test
    fun `실패한 로그인은 앞서 로그인한 계정을 지우지 않는다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.credential = { throw androidx.credentials.exceptions.GetCredentialCancellationException("closed") }
        assertSame(SocialLoginResult.Cancelled, p.signIn(activity, SignInOptions.DEFAULT))
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals(listOf("person@example.com"), gateway.revokedEmails)
    }

    @Test
    fun `프로세스 재시작 뒤에는 이메일을 넘겨 철회한다 — 앞뒤 공백은 지운다`() = runTest(dispatcher) {
        val restarted: SocialLoginProvider<android.app.Activity> = provider()
        assertEquals(SocialLoginResult.Success(Unit), GoogleLogin.unlink(restarted, "  saved@example.com "))
        assertEquals(listOf("saved@example.com"), gateway.revokedEmails)
        assertEquals(listOf("revokeAuthorization", "revoke", "clear"), gateway.calls)
    }

    @Test
    fun `넘긴 이메일이 기억한 계정보다 우선한다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        assertEquals(SocialLoginResult.Success(Unit), GoogleLogin.unlink(p, "other@example.com"))
        assertEquals(listOf("other@example.com"), gateway.revokedEmails)
    }

    @Test
    fun `철회 실패는 분류하고 계정을 기억해 다시 시도할 수 있다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revokeAuthorization = { throw apiError(GoogleErrors.STATUS_NETWORK_ERROR) }
        val network = p.unlink().failure()
        assertEquals(FailureKind.NETWORK, network.kind)
        assertTrue(network.kind.retryable)
        assertEquals("ApiException(7)", network.vendorCode)
        // 주 경로가 실패하면 보조 정리를 하지 않는다 — 기기 상태가 남아 재시도·재로그인이 가능하다
        assertEquals(listOf("revokeAuthorization"), gateway.calls)

        gateway.revokeAuthorization = { throw IOException("offline") }
        assertEquals(FailureKind.NETWORK, p.unlink().failure().kind)

        gateway.revokeAuthorization = {}
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals(3, gateway.revokedEmails.size)
    }

    @Test
    fun `철회 상태 코드 — 로그인 필요·사용자 취소·기능 없음·설정 오류`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revokeAuthorization = { throw apiError(GoogleErrors.STATUS_SIGN_IN_REQUIRED) }
        assertEquals(FailureKind.NOT_SIGNED_IN, p.unlink().failure().kind)
        gateway.revokeAuthorization = { throw apiError(GoogleErrors.STATUS_SIGN_IN_CANCELLED) }
        assertSame(SocialLoginResult.Cancelled, p.unlink())
        gateway.revokeAuthorization = { throw apiError(GoogleErrors.STATUS_API_NOT_CONNECTED) }
        assertEquals(FailureKind.UNSUPPORTED, p.unlink().failure().kind)
        gateway.revokeAuthorization = { throw apiError(GoogleErrors.STATUS_DEVELOPER_ERROR) }
        assertEquals(FailureKind.CONFIGURATION, p.unlink().failure().kind)
    }

    // 0.1.1 — 동의 이력이 없는 계정의 철회. 실기기의 Google Play 서비스가 낸 상태 그대로다
    private fun nothingToRevoke() = ApiException(Status(GoogleErrors.STATUS_DEVELOPER_ERROR, "[28470] No access token was returned"))

    @Test
    fun `동의 이력이 없어 철회할 권한이 없으면 성공이고 상태를 지운다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revokeAuthorization = { throw nothingToRevoke() }
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals(listOf("revokeAuthorization", "revoke", "clear"), gateway.calls)
        // 계정도 잊는다 — 다시 부르면 Google 을 부르지 않은 NOT_SIGNED_IN
        gateway.calls.clear()
        assertEquals(GoogleLoginProvider.VENDOR_NO_ACCOUNT, p.unlink().failure().vendorCode)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `이메일로 철회할 때도 동의 이력이 없으면 성공이다`() = runTest(dispatcher) {
        val restarted: SocialLoginProvider<Activity> = provider(GoogleSignInMode.BOTTOM_SHEET)
        gateway.revokeAuthorization = { throw nothingToRevoke() }
        gateway.revoke = { throw apiError(GoogleErrors.STATUS_SIGN_IN_REQUIRED) }
        assertEquals(SocialLoginResult.Success(Unit), GoogleLogin.unlink(restarted, "saved@example.com"))
        assertEquals(listOf("revokeAuthorization", "revoke", "clear"), gateway.calls)
    }

    @Test
    fun `메시지가 다른 상태 코드 10 은 그대로 CONFIGURATION 이다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revokeAuthorization = { throw ApiException(Status(GoogleErrors.STATUS_DEVELOPER_ERROR, "[10] DEVELOPER_ERROR")) }
        val f = p.unlink().failure()
        assertEquals(FailureKind.CONFIGURATION, f.kind)
        assertEquals("ApiException(10)", f.vendorCode)
        // 주 경로가 실패했으니 보조 정리를 하지 않고 계정을 기억한다
        assertEquals(listOf("revokeAuthorization"), gateway.calls)
        gateway.revokeAuthorization = {}
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
    }

    @Test
    fun `같은 메시지라도 상태 코드가 10 이 아니면 성공으로 바꾸지 않는다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revokeAuthorization = {
            throw ApiException(Status(GoogleErrors.STATUS_NETWORK_ERROR, "[28470] No access token was returned"))
        }
        assertEquals(FailureKind.NETWORK, p.unlink().failure().kind)
        gateway.revokeAuthorization = { throw IOException("[28470] No access token was returned") }
        assertEquals(FailureKind.NETWORK, p.unlink().failure().kind)
    }

    @Test
    fun `기본 모드의 Google Sign-In 철회는 이 판별을 쓰지 않는다`() = runTest(dispatcher) {
        val p = provider(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN).signedIn()
        gateway.revoke = { throw nothingToRevoke() }
        assertEquals(FailureKind.CONFIGURATION, p.unlink().failure().kind)
    }

    @Test
    fun `isNothingToRevoke — 상태 코드 10 과 철회할 토큰 없음 메시지가 함께일 때만`() {
        assertTrue(GoogleErrors.isNothingToRevoke(nothingToRevoke()))
        assertTrue(GoogleErrors.isNothingToRevoke(ApiException(Status(10, "No access token was returned"))))
        assertFalse(GoogleErrors.isNothingToRevoke(apiError(GoogleErrors.STATUS_DEVELOPER_ERROR)))
        assertFalse(GoogleErrors.isNothingToRevoke(ApiException(Status(8, "[28470] No access token was returned"))))
        assertFalse(GoogleErrors.isNothingToRevoke(IllegalStateException("[28470] No access token was returned")))
        assertFalse(GoogleErrors.isNothingToRevoke(CancellationException("[28470]")))
    }

    @Test
    fun `Google Play 서비스가 revokeAccess 를 모르면 UNSUPPORTED`() = runTest(dispatcher) {
        val p = provider().signedIn()
        val e = UnsupportedApiCallException(Feature("auth_api_revoke", 1L))
        gateway.revokeAuthorization = { throw e }
        val f = p.unlink().failure()
        assertEquals(FailureKind.UNSUPPORTED, f.kind)
        assertEquals("UnsupportedApiCallException", f.vendorCode)
        assertSame(e, f.cause)
        assertTrue(f.message.contains("업데이트"))
    }

    @Test
    fun `보조 철회·정리의 실패는 결과에 넣지 않는다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revoke = { throw apiError(GoogleErrors.STATUS_SIGN_IN_REQUIRED) }
        gateway.clear = { throw ClearCredentialUnknownException("x") }
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals(listOf("revokeAuthorization", "revoke", "clear"), gateway.calls)
    }

    @Test
    fun `기본 모드 설정이어도 호출별 Credential Manager 로그인이면 그 경로로 철회한다`() = runTest(dispatcher) {
        val p = provider(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN)
        assertTrue(p.signIn(activity, extension(GoogleSignInMode.BUTTON)) is SocialLoginResult.Success)
        gateway.calls.clear()
        gateway.revoke = { throw apiError(GoogleErrors.STATUS_SIGN_IN_REQUIRED) }
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals(listOf("revokeAuthorization", "revoke", "clear"), gateway.calls)
    }

    @Test
    fun `기본 모드 로그인 뒤에는 Google Sign-In 철회가 주 경로이고 이메일을 알면 보조로 한 번 더 철회한다`() = runTest(dispatcher) {
        val p = provider(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN).signedIn()
        gateway.revokeAuthorization = { throw apiError(GoogleErrors.STATUS_NETWORK_ERROR) }
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        assertEquals(listOf("revoke", "revokeAuthorization", "clear"), gateway.calls)
        assertEquals(listOf("person@example.com"), gateway.revokedEmails)
    }

    @Test
    fun `Credential Manager 설정에서 호출별 기본 모드로 로그인했으면 Google Sign-In 으로 철회한다`() = runTest(dispatcher) {
        gateway.legacyAccount = { legacyAccount(email = null) }
        val p = provider(GoogleSignInMode.BUTTON)
        assertTrue(p.signIn(activity, extension(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN)) is SocialLoginResult.Success)
        gateway.calls.clear()
        assertEquals(SocialLoginResult.Success(Unit), p.unlink())
        // 이메일을 모르면 보조 철회는 건너뛴다
        assertEquals(listOf("revoke", "clear"), gateway.calls)

        p.signIn(activity, extension(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN))
        gateway.revoke = { throw apiError(GoogleErrors.STATUS_NETWORK_ERROR) }
        assertEquals(FailureKind.NETWORK, p.unlink().failure().kind)
    }

    @Test
    fun `기본 모드에서 로그인 없이 이메일을 넘기면 Google Sign-In 철회 뒤 그 계정도 철회한다`() = runTest(dispatcher) {
        val p = provider(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN)
        assertEquals(SocialLoginResult.Success(Unit), GoogleLogin.unlink(p, "saved@example.com"))
        assertEquals(listOf("revoke", "revokeAuthorization", "clear"), gateway.calls)
    }

    @Test
    fun `설정 안 됨이면 이메일을 넘겨도 Google 을 부르지 않은 CONFIGURATION`() = runTest(dispatcher) {
        val p = GoogleLoginProvider(googleConfig("", GoogleSignInMode.BUTTON), gateway, dispatcher)
        assertEquals(FailureKind.CONFIGURATION, GoogleLogin.unlink(p, "saved@example.com").failure().kind)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `결과·메시지에 이메일이 나오지 않는다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revokeAuthorization = { throw apiError(GoogleErrors.STATUS_NETWORK_ERROR) }
        val f = p.unlink().failure()
        assertFalse(f.message.contains("person@example.com"))
        assertFalse(f.toString().contains("person@example.com"))
        val none = provider().unlink().failure()
        assertFalse(none.toString().contains("@"))
    }

    @Test
    fun `코루틴 취소는 결과로 바꾸지 않는다`() = runTest(dispatcher) {
        val p = provider().signedIn()
        gateway.revokeAuthorization = { throw CancellationException("left") }
        try {
            p.unlink()
            fail()
        } catch (e: CancellationException) {
            assertEquals("left", e.message)
        }
    }

    @Test
    fun `프로그래머 오류 — 공백 이메일·다른 Provider 는 던진다`() = runTest(dispatcher) {
        for (blank in listOf("", "   ")) {
            try {
                GoogleLogin.unlink(provider(), blank)
                fail()
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("accountEmail"))
            }
        }
        val other = object : SocialLoginProvider<Activity> {
            override val id: ProviderId = ProviderId.GOOGLE
            override val capabilities = emptySet<net.plumt.sociallogin.Capability>()
            override suspend fun signIn(host: Activity, options: SignInOptions): SocialLoginResult<SocialAccount> =
                SocialLoginResult.Cancelled
        }
        try {
            GoogleLogin.unlink(other, "saved@example.com")
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("GoogleLogin.provider"))
        }
        assertTrue(gateway.calls.isEmpty())
    }
}
