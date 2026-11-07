package net.plumt.sociallogin.google

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.TransactionTooLargeException
import androidx.credentials.exceptions.ClearCredentialUnknownException
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialLoginManager
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.google.internal.GoogleErrors
import net.plumt.sociallogin.google.internal.GoogleLoginProvider
import net.plumt.sociallogin.google.internal.GoogleSignInHostResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/** C1 — 기본 모드(Google Sign-In 계정 선택 + 투명 호스트). */
@RunWith(RobolectricTestRunner::class)
class GoogleLegacyTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeGoogleGateway()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun provider(config: GoogleLoginConfig = GoogleLoginConfig(CLIENT_ID)) =
        GoogleLoginProvider(config, gateway, dispatcher)

    private fun SocialLoginResult<*>.failure() = this as SocialLoginResult.Failure

    private fun apiError(code: Int) = ApiException(Status(code))

    @Test
    fun `기본 모드는 Credential Manager 를 부르지 않고 Google Sign-In 으로 로그인한다`() = runTest(dispatcher) {
        val account = (provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Success).value
        assertEquals(listOf("legacyIntent", "host", "parse"), gateway.calls)
        assertTrue(gateway.options.isEmpty())
        assertEquals(listOf(CLIENT_ID), gateway.legacyClientIds)
        assertEquals(ProviderId.GOOGLE, account.providerId)
        assertEquals("legacy.id.token", account.token.idToken)
        assertNull(account.token.accessToken)
        assertEquals("person@example.com", account.email)
        assertEquals("홍길동", account.displayName)
        assertEquals("https://img.example.com/p.png", account.profileImageUrl)
        assertSame("계정 선택 화면은 메인 디스패처에서 띄운다", dispatcher, gateway.interceptor)
    }

    @Test
    fun `빈 값은 null 로 옮기고 ID 토큰이 없으면 UNKNOWN`() = runTest(dispatcher) {
        gateway.legacyAccount = { legacyAccount(email = " ", displayName = null, photoUrl = "") }
        val acc = (provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Success).value
        assertNull(acc.email)
        assertNull(acc.displayName)
        assertNull(acc.profileImageUrl)
        for (blank in listOf(null, "", "  ")) {
            gateway.legacyAccount = { legacyAccount(idToken = blank) }
            val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
            assertEquals(FailureKind.UNKNOWN, f.kind)
            assertTrue(f.message, f.message.contains("serverClientId"))
        }
    }

    @Test
    fun `nonce 는 담을 수 없어 SDK 를 부르지 않고 UNSUPPORTED`() = runTest(dispatcher) {
        val f = provider().signIn(activity, SignInOptions("n-1")).failure()
        assertEquals(FailureKind.UNSUPPORTED, f.kind)
        assertTrue(f.message, f.message.contains("nonce"))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `호스트 결말 — 취소·바쁨·시작 안 됨·실패`() = runTest(dispatcher) {
        gateway.hostResult = { GoogleSignInHostResult.HostGone }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))

        gateway.hostResult = { GoogleSignInHostResult.ActivityResult(Activity.RESULT_CANCELED, null) }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))

        gateway.hostResult = { GoogleSignInHostResult.ActivityResult(Activity.RESULT_OK, null) }
        assertEquals(FailureKind.UNKNOWN, provider().signIn(activity, SignInOptions.DEFAULT).failure().kind)

        gateway.hostResult = { GoogleSignInHostResult.Busy }
        val busy = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.BUSY, busy.kind)
        assertEquals(GoogleLoginProvider.VENDOR_HOST_BUSY, busy.vendorCode)

        gateway.hostResult = { GoogleSignInHostResult.NotStarted }
        val ns = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.INTERRUPTED, ns.kind)
        assertEquals(GoogleLoginProvider.VENDOR_HOST_NOT_STARTED, ns.vendorCode)

        val anf = ActivityNotFoundException("no gms")
        gateway.hostResult = { GoogleSignInHostResult.Failed(anf) }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.UNSUPPORTED, f.kind)
        assertEquals("ActivityNotFoundException", f.vendorCode)
        assertSame(anf, f.cause)
        assertFalse(gateway.calls.contains("parse"))
    }

    @Test
    fun `RESULT_CANCELED 라도 결과 데이터의 설정 오류를 취소로 묻지 않는다`() = runTest(dispatcher) {
        gateway.hostResult = { GoogleSignInHostResult.ActivityResult(Activity.RESULT_CANCELED, Intent("r")) }
        gateway.legacyAccount = { throw apiError(GoogleErrors.STATUS_DEVELOPER_ERROR) }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.CONFIGURATION, f.kind)
        assertEquals("ApiException(10)", f.vendorCode)
        assertTrue(f.message, f.message.contains("SHA-1"))

        gateway.legacyAccount = { throw apiError(GoogleErrors.STATUS_SIGN_IN_CANCELLED) }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
    }

    @Test
    fun `Google Play 서비스 상태 코드 분류`() {
        val table = mapOf(
            1 to FailureKind.UNSUPPORTED, 2 to FailureKind.UNSUPPORTED, 3 to FailureKind.UNSUPPORTED,
            4 to FailureKind.NOT_SIGNED_IN, 7 to FailureKind.NETWORK, 8 to FailureKind.UNKNOWN,
            9 to FailureKind.UNSUPPORTED, 10 to FailureKind.CONFIGURATION, 14 to FailureKind.INTERRUPTED,
            15 to FailureKind.NETWORK, 16 to FailureKind.INTERRUPTED, 18 to FailureKind.UNSUPPORTED,
            12500 to FailureKind.UNKNOWN, 12502 to FailureKind.BUSY, 0 to FailureKind.UNKNOWN,
            -1 to FailureKind.UNKNOWN, 99999 to FailureKind.UNKNOWN,
        )
        table.forEach { (code, kind) ->
            assertEquals("status $code", kind, GoogleErrors.kindOfStatus(code))
            val f = GoogleErrors.toResult(apiError(code), "signIn") as SocialLoginResult.Failure
            assertEquals(kind, f.kind)
            assertEquals("ApiException($code)", f.vendorCode)
            assertEquals(ProviderId.GOOGLE, f.providerId)
        }
        assertSame(SocialLoginResult.Cancelled, GoogleErrors.toResult(apiError(12501), "signIn"))
        assertEquals(FailureKind.UNKNOWN, GoogleErrors.kindOfStatus(12501))
    }

    @Test
    fun `호스트가 계정 선택 화면을 Binder 한도로 못 띄우면 전용 실패`() = runTest(dispatcher) {
        // startActivity 의 RemoteException 은 RuntimeException 으로 감싸져 온다
        val wrapped = RuntimeException(TransactionTooLargeException("data parcel size 622060 bytes"))
        gateway.hostResult = { GoogleSignInHostResult.Failed(wrapped) }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.UNSUPPORTED, f.kind)
        assertEquals(GoogleErrors.TRANSACTION_TOO_LARGE, f.vendorCode)
        assertSame(wrapped, f.cause)
        assertFalse(f.message.contains("622060"))

        // 원인 없이 문구로만 옮겨 온 경우
        gateway.hostResult = { GoogleSignInHostResult.Failed(IllegalStateException("android.os.TransactionTooLargeException: x")) }
        assertEquals(GoogleErrors.TRANSACTION_TOO_LARGE, provider().signIn(activity, SignInOptions.DEFAULT).failure().vendorCode)
    }

    @Test
    fun `인텐트 생성·호스트 시작이 던지면 분류한다`() = runTest(dispatcher) {
        gateway.hostResult = { throw ActivityNotFoundException() }
        assertEquals(FailureKind.UNSUPPORTED, provider().signIn(activity, SignInOptions.DEFAULT).failure().kind)
        gateway.hostResult = { throw java.io.IOException("net") }
        assertEquals(FailureKind.NETWORK, provider().signIn(activity, SignInOptions.DEFAULT).failure().kind)
    }

    @Test
    fun `코루틴 취소는 결과로 바꾸지 않는다`() = runTest(dispatcher) {
        gateway.hostResult = { throw CancellationException("left") }
        try {
            provider().signIn(activity, SignInOptions.DEFAULT)
            fail()
        } catch (e: CancellationException) {
            assertEquals("left", e.message)
        }
    }

    @Test
    fun `기본 모드는 연결 해제를 지원한다 — 권한 철회 후 자격 증명 상태도 지운다`() = runTest(dispatcher) {
        assertEquals(setOf(Capability.SIGN_OUT, Capability.UNLINK), provider().capabilities)
        assertEquals(SocialLoginResult.Success(Unit), provider().unlink())
        assertEquals(listOf("revoke", "clear"), gateway.calls)

        gateway.calls.clear()
        gateway.revoke = { throw apiError(GoogleErrors.STATUS_SIGN_IN_REQUIRED) }
        val f = provider().unlink().failure()
        assertEquals(FailureKind.NOT_SIGNED_IN, f.kind)
        assertEquals(listOf("revoke"), gateway.calls)

        gateway.revoke = { throw apiError(GoogleErrors.STATUS_NETWORK_ERROR) }
        assertEquals(FailureKind.NETWORK, provider().unlink().failure().kind)
    }

    @Test
    fun `철회가 성공하면 보조 정리 실패는 결과에 넣지 않는다`() = runTest(dispatcher) {
        gateway.clear = { throw ClearCredentialUnknownException("x") }
        assertEquals(SocialLoginResult.Success(Unit), provider().unlink())
        assertEquals(SocialLoginResult.Success(Unit), provider().signOut())
    }

    @Test
    fun `로그아웃 — 기본 모드는 Google Sign-In 을 지우고 그 실패만 알린다`() = runTest(dispatcher) {
        assertEquals(SocialLoginResult.Success(Unit), provider().signOut())
        assertEquals(listOf("legacySignOut", "clear"), gateway.calls)
        gateway.legacySignOut = { throw apiError(GoogleErrors.STATUS_NETWORK_ERROR) }
        val f = provider().signOut().failure()
        assertEquals(FailureKind.NETWORK, f.kind)
        assertTrue(f.message.contains("signOut"))
    }

    @Test
    fun `로그아웃 — Credential Manager 모드의 보조 Google Sign-In 정리 실패는 결과에 넣지 않는다`() = runTest(dispatcher) {
        gateway.legacySignOut = { throw apiError(GoogleErrors.STATUS_SERVICE_MISSING) }
        assertEquals(SocialLoginResult.Success(Unit), provider(GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BUTTON)).signOut())
    }

    @Test
    fun `보조 정리 중 코루틴 취소는 다시 던진다`() = runTest(dispatcher) {
        gateway.clear = { throw CancellationException("c") }
        try {
            provider().signOut()
            fail()
        } catch (e: CancellationException) {
            assertEquals("c", e.message)
        }
    }

    @Test
    fun `M2 — 호출별 확장으로 같은 Provider 에서 조용한 시트 로그인과 기본 로그인을 오간다`() = runTest(dispatcher) {
        val silent = SignInOptions(
            null,
            listOf(GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, filterByAuthorizedAccounts = true, autoSelectEnabled = true)),
        )
        val p = provider()
        assertTrue(p.signIn(activity, silent) is SocialLoginResult.Success)
        val sheet = gateway.options.single() as GetGoogleIdOption
        assertTrue(sheet.filterByAuthorizedAccounts)
        assertTrue(sheet.autoSelectEnabled)
        assertFalse(gateway.calls.contains("host"))

        assertTrue(p.signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
        assertTrue(gateway.calls.contains("host"))

        // 버튼 흐름은 nonce 를 담는다
        val button = SignInOptions("n-9", listOf(GoogleSignInExtension(GoogleSignInMode.BUTTON)))
        assertTrue(p.signIn(activity, button) is SocialLoginResult.Success)
        assertEquals("n-9", (gateway.options.last() as GetSignInWithGoogleOption).nonce)

        // 설정이 시트여도 확장으로 기본 모드를 고를 수 있다
        gateway.calls.clear()
        val toLegacy = SignInOptions(null, listOf(GoogleSignInExtension(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN)))
        assertTrue(provider(GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BOTTOM_SHEET)).signIn(activity, toLegacy) is SocialLoginResult.Success)
        assertEquals(listOf("legacyIntent", "host", "parse"), gateway.calls)
    }

    @Test
    fun `확장 값 객체 — 동등성과 toString`() {
        val a = GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, true, false)
        assertEquals(a, GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, true, false))
        assertEquals(a.hashCode(), GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, true, false).hashCode())
        assertFalse(a == GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, true, true))
        assertFalse(a == GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, false, false))
        assertFalse(a == GoogleSignInExtension(GoogleSignInMode.BUTTON))
        assertFalse(a.equals("x"))
        assertEquals(
            "GoogleSignInExtension(mode=BOTTOM_SHEET, filterByAuthorizedAccounts=true, autoSelectEnabled=false)",
            a.toString(),
        )
    }

    @Test
    fun `Manager 를 거쳐도 기본 모드의 연결 해제가 불린다`() = runTest(dispatcher) {
        val m = SocialLoginManager.builder<Activity>().register(provider()).build()
        assertEquals(SocialLoginResult.Success(Unit), m.unlink(ProviderId.GOOGLE))
        assertTrue(gateway.calls.contains("revoke"))
    }

    @Test
    fun `무작위 결말에서도 값만 돌려준다`() = runTest(dispatcher) {
        val random = Random(2027)
        val outcomes: List<() -> GoogleSignInHostResult> = listOf(
            { GoogleSignInHostResult.Busy }, { GoogleSignInHostResult.HostGone }, { GoogleSignInHostResult.NotStarted },
            { GoogleSignInHostResult.Failed(RuntimeException(TransactionTooLargeException())) },
            { GoogleSignInHostResult.Failed(IllegalStateException()) },
            { GoogleSignInHostResult.ActivityResult(random.nextInt(-2, 2), if (random.nextBoolean()) Intent("r") else null) },
        )
        repeat(500) {
            gateway.hostResult = { outcomes.random(random)() }
            gateway.legacyAccount = {
                when (random.nextInt(3)) {
                    0 -> legacyAccount()
                    1 -> throw apiError(random.nextInt(0, 13000))
                    else -> legacyAccount(idToken = null)
                }
            }
            val r = provider().signIn(activity, SignInOptions.DEFAULT)
            assertTrue(r is SocialLoginResult.Success || r is SocialLoginResult.Failure || r === SocialLoginResult.Cancelled)
        }
    }
}
