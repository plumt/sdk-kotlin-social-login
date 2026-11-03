package net.plumt.sociallogin.google

import android.app.Activity
import android.os.TransactionTooLargeException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
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
import kotlin.random.Random

/** 계정이 많은 기기의 Binder 한도(TransactionTooLargeException) 폴백 사슬. */
@RunWith(RobolectricTestRunner::class)
class GoogleFallbackTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeGoogleGateway()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun provider(config: GoogleLoginConfig = GoogleLoginConfig(CLIENT_ID)) =
        GoogleLoginProvider(config, gateway, dispatcher)

    /** 시도마다 차례로 결과를 낸다. */
    private fun sequence(vararg steps: () -> androidx.credentials.Credential) {
        var i = 0
        gateway.credential = { steps[i++]() }
    }

    private fun ttl() = TransactionTooLargeException("data parcel size 622060 bytes")

    @Test
    fun `시트가 한도에 걸리면 버튼 흐름으로 한 번 다시 시도하고 nonce 를 유지한다`() = runTest(dispatcher) {
        sequence({ throw ttl() }, { googleCredential() })
        val result = provider().signIn(activity, SignInOptions("n1"))
        assertTrue(result is SocialLoginResult.Success)
        assertEquals(2, gateway.calls.size)
        assertEquals("n1", (gateway.options[0] as GetGoogleIdOption).nonce)
        val retry = gateway.options[1] as GetSignInWithGoogleOption
        assertEquals("n1", retry.nonce)
        assertEquals(CLIENT_ID, retry.serverClientId)
    }

    @Test
    fun `시트 전용 선택지를 켠 설정도 버튼 흐름으로 재시도한다`() = runTest(dispatcher) {
        sequence({ throw ttl() }, { googleCredential() })
        val config = GoogleLoginConfig(CLIENT_ID, filterByAuthorizedAccounts = true, autoSelectEnabled = true)
        assertTrue(provider(config).signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
        assertTrue(gateway.options[1] is GetSignInWithGoogleOption)
    }

    @Test
    fun `Credential Manager 예외의 원인 사슬에 있어도 재시도한다`() = runTest(dispatcher) {
        sequence({ throw GetCredentialUnknownException("x").also { it.initCause(RuntimeException(ttl())) } }, { googleCredential() })
        assertTrue(provider().signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun `원인 없이 문구로만 옮겨 와도 재시도한다`() = runTest(dispatcher) {
        sequence({ throw GetCredentialUnknownException("android.os.TransactionTooLargeException: data parcel") }, { googleCredential() })
        assertTrue(provider().signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
    }

    @Test
    fun `둘 다 한도에 걸리면 전용 vendorCode 의 UNSUPPORTED, 첫 원인은 suppressed`() = runTest(dispatcher) {
        val first = ttl()
        val second = ttl()
        sequence({ throw first }, { throw second })
        val f = provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Failure
        assertEquals(FailureKind.UNSUPPORTED, f.kind)
        assertEquals(GoogleErrors.TRANSACTION_TOO_LARGE, f.vendorCode)
        assertEquals("TransactionTooLargeException", f.vendorCode)
        assertEquals(ProviderId.GOOGLE, f.providerId)
        assertSame(second, f.cause)
        assertEquals(listOf<Throwable>(first), second.suppressed.toList())
        assertTrue(f.message.contains("Binder"))
        assertFalse(f.message.contains("622060"))
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun `재시도에서 사용자가 닫으면 Cancelled`() = runTest(dispatcher) {
        sequence({ throw ttl() }, { throw GetCredentialCancellationException("closed") })
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
    }

    @Test
    fun `재시도의 다른 실패는 그대로 분류하고 첫 원인을 suppressed 로 남긴다`() = runTest(dispatcher) {
        val first = ttl()
        val second = NoCredentialException("none")
        sequence({ throw first }, { throw second })
        val f = provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Failure
        assertEquals(FailureKind.NO_CREDENTIAL, f.kind)
        assertSame(second, f.cause)
        assertEquals(listOf<Throwable>(first), second.suppressed.toList())
    }

    @Test
    fun `같은 예외 인스턴스가 두 번 나와도 자기 자신을 suppressed 로 붙이지 않는다`() = runTest(dispatcher) {
        val same = ttl()
        sequence({ throw same }, { throw same })
        val f = provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Failure
        assertEquals(GoogleErrors.TRANSACTION_TOO_LARGE, f.vendorCode)
        assertEquals(0, same.suppressed.size)
    }

    @Test
    fun `버튼 흐름에서 처음부터 걸리면 재시도 없이 전용 실패`() = runTest(dispatcher) {
        val e = ttl()
        sequence({ throw e })
        val f = provider(GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BUTTON)).signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Failure
        assertEquals(FailureKind.UNSUPPORTED, f.kind)
        assertEquals(GoogleErrors.TRANSACTION_TOO_LARGE, f.vendorCode)
        assertSame(e, f.cause)
        assertEquals(1, gateway.calls.size)
    }

    @Test
    fun `한도와 무관한 실패는 재시도하지 않는다`() = runTest(dispatcher) {
        sequence({ throw NoCredentialException("none") }, { googleCredential() })
        val f = provider().signIn(activity, SignInOptions.DEFAULT) as SocialLoginResult.Failure
        assertEquals(FailureKind.NO_CREDENTIAL, f.kind)
        assertEquals(1, gateway.calls.size)
        sequence({ throw GetCredentialCancellationException() }, { googleCredential() })
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun `재시도 중 코루틴 취소는 다시 던진다`() = runTest(dispatcher) {
        sequence({ throw ttl() }, { throw CancellationException("retry-cancel") })
        try {
            provider().signIn(activity, SignInOptions.DEFAULT)
            fail()
        } catch (e: CancellationException) {
            assertEquals("retry-cancel", e.message)
        }
    }

    @Test
    fun `한도 판정 — 사슬 깊이·순환·문구 없음`() {
        assertTrue(GoogleErrors.isTransactionTooLarge(ttl()))
        assertFalse(GoogleErrors.isTransactionTooLarge(RuntimeException()))
        assertFalse(GoogleErrors.isTransactionTooLarge(GetCredentialUnknownException()))
        assertFalse(GoogleErrors.isTransactionTooLarge(GetCredentialUnknownException("other")))
        var deep: Throwable = ttl()
        repeat(15) { deep = RuntimeException(deep) }
        assertTrue(GoogleErrors.isTransactionTooLarge(deep))
        deep = RuntimeException(deep)
        assertFalse(GoogleErrors.isTransactionTooLarge(deep))
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(GoogleErrors.isTransactionTooLarge(a))
    }

    @Test
    fun `무작위 실패 사슬에서도 값만 돌려주고 시도는 최대 두 번`() = runTest(dispatcher) {
        val random = Random(2026)
        val failures: List<() -> Throwable> = listOf(
            { ttl() }, { NoCredentialException("n") }, { GetCredentialCancellationException() },
            { GetCredentialUnknownException("TransactionTooLargeException") }, { IllegalStateException() },
        )
        repeat(500) {
            gateway.calls.clear()
            sequence(
                { if (random.nextBoolean()) throw failures.random(random)() else googleCredential() },
                { if (random.nextBoolean()) throw failures.random(random)() else googleCredential() },
            )
            val mode = GoogleSignInMode.entries.random(random)
            val r = provider(GoogleLoginConfig(CLIENT_ID, mode)).signIn(activity, SignInOptions.DEFAULT)
            assertTrue(r is SocialLoginResult.Success || r is SocialLoginResult.Failure || r === SocialLoginResult.Cancelled)
            assertTrue(gateway.calls.size in 1..2)
        }
    }
}
