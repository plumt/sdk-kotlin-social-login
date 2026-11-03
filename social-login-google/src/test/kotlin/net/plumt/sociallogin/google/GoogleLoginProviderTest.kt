package net.plumt.sociallogin.google

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.credentials.Credential
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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
import net.plumt.sociallogin.SocialLoginManager
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.google.internal.GoogleCredentials
import net.plumt.sociallogin.google.internal.GoogleGateway
import net.plumt.sociallogin.google.internal.GoogleLoginProvider
import net.plumt.sociallogin.google.internal.GoogleOptions
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
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

internal const val CLIENT_ID = "1234-abc.apps.googleusercontent.com"

internal fun googleCredential(
    idToken: String = "header.payload.sig",
    id: String = "person@example.com",
    displayName: String? = "홍길동",
    picture: String? = "https://img.example.com/p.png",
    type: String = GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
): CustomCredential {
    val data = GoogleIdTokenCredential(
        id = id,
        idToken = idToken,
        displayName = displayName,
        familyName = null,
        givenName = null,
        profilePictureUri = picture?.let { Uri.parse(it) },
        phoneNumber = null,
    ).data
    return CustomCredential(type, data)
}

internal class FakeGoogleGateway : GoogleGateway {
    val calls = mutableListOf<String>()
    val options = mutableListOf<CredentialOption>()
    var interceptor: CoroutineContext.Element? = null
    var credential: suspend () -> Credential = { googleCredential() }
    var clear: suspend () -> Unit = {}

    override suspend fun getCredential(activity: Activity, option: CredentialOption): Credential {
        calls += "get"
        options += option
        interceptor = currentCoroutineContext()[ContinuationInterceptor]
        return credential()
    }

    override suspend fun clearCredentialState() {
        calls += "clear"
        clear()
    }
}

@RunWith(RobolectricTestRunner::class)
class GoogleLoginProviderTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeGoogleGateway()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun provider(config: GoogleLoginConfig = GoogleLoginConfig(CLIENT_ID)) =
        GoogleLoginProvider(config, gateway, dispatcher)

    private fun SocialLoginResult<*>.failure() = this as SocialLoginResult.Failure

    @Test
    fun `식별자와 지원 동작`() {
        assertEquals(ProviderId.GOOGLE, provider().id)
        assertEquals(setOf(Capability.SIGN_OUT), provider().capabilities)
    }

    @Test
    fun `로그인 성공 — ID 토큰·이메일·이름·사진을 옮기고 액세스 토큰은 없다`() = runTest(dispatcher) {
        val account = (provider().signIn(activity, SignInOptions("nonce-1")) as SocialLoginResult.Success).value
        assertEquals(ProviderId.GOOGLE, account.providerId)
        assertEquals("header.payload.sig", account.token.idToken)
        assertNull(account.token.accessToken)
        assertNull(account.token.refreshToken)
        assertEquals("person@example.com", account.email)
        assertEquals("홍길동", account.displayName)
        assertEquals("https://img.example.com/p.png", account.profileImageUrl)
        val option = gateway.options.single() as GetGoogleIdOption
        assertEquals(CLIENT_ID, option.serverClientId)
        assertEquals("nonce-1", option.nonce)
        assertSame(dispatcher, gateway.interceptor)
    }

    @Test
    fun `버튼 흐름은 Sign in with Google 옵션을 쓴다`() = runTest(dispatcher) {
        gateway.credential = { googleCredential(type = GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL) }
        val result = provider(GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BUTTON)).signIn(activity, SignInOptions.DEFAULT)
        assertTrue(result is SocialLoginResult.Success)
        val option = gateway.options.single() as GetSignInWithGoogleOption
        assertEquals(CLIENT_ID, option.serverClientId)
        assertNull(option.nonce)
    }

    @Test
    fun `사용자 취소는 Cancelled`() = runTest(dispatcher) {
        gateway.credential = { throw GetCredentialCancellationException("closed") }
        assertSame(SocialLoginResult.Cancelled, provider().signIn(activity, SignInOptions.DEFAULT))
    }

    @Test
    fun `계정이 없으면 NO_CREDENTIAL, 원인과 타입을 보존한다`() = runTest(dispatcher) {
        val e = NoCredentialException("none")
        gateway.credential = { throw e }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.NO_CREDENTIAL, f.kind)
        assertSame(e, f.cause)
        assertEquals(e.type, f.vendorCode)
        assertEquals(ProviderId.GOOGLE, f.providerId)
    }

    @Test
    fun `Google 이 아닌 자격 증명은 UNKNOWN`() = runTest(dispatcher) {
        gateway.credential = { PasswordCredential("user", "pw") }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertEquals(PasswordCredential.TYPE_PASSWORD_CREDENTIAL, f.vendorCode)
        gateway.credential = { CustomCredential("com.example.other", Bundle()) }
        assertEquals("com.example.other", provider().signIn(activity, SignInOptions.DEFAULT).failure().vendorCode)
    }

    @Test
    fun `깨진 Google 자격 증명 데이터는 UNKNOWN`() = runTest(dispatcher) {
        gateway.credential = { CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle()) }
        val f = provider().signIn(activity, SignInOptions.DEFAULT).failure()
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertEquals("GoogleIdTokenParsingException", f.vendorCode)
    }

    @Test
    fun `게이트웨이의 망 예외는 NETWORK`() = runTest(dispatcher) {
        gateway.credential = { throw IOException() }
        assertEquals(FailureKind.NETWORK, provider().signIn(activity, SignInOptions.DEFAULT).failure().kind)
    }

    @Test
    fun `로그아웃은 자격 증명 상태를 지운다, 실패는 분류한다`() = runTest(dispatcher) {
        assertEquals(SocialLoginResult.Success(Unit), provider().signOut())
        assertEquals(listOf("clear"), gateway.calls)
        gateway.clear = { throw ClearCredentialUnknownException("x") }
        val f = provider().signOut().failure()
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertTrue(f.message.contains("signOut"))
    }

    @Test
    fun `연결 해제·갱신은 UNSUPPORTED 이고 게이트웨이를 부르지 않는다`() = runTest(dispatcher) {
        val u = provider().unlink().failure()
        assertEquals(FailureKind.UNSUPPORTED, u.kind)
        assertTrue(u.message.contains("철회"))
        val r = provider().refreshToken().failure()
        assertEquals(FailureKind.UNSUPPORTED, r.kind)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `코루틴 취소는 결과로 바꾸지 않는다`() = runTest(dispatcher) {
        gateway.credential = { throw CancellationException("c") }
        try {
            provider().signIn(activity, SignInOptions.DEFAULT)
            fail()
        } catch (e: CancellationException) {
            assertEquals("c", e.message)
        }
    }

    @Test
    fun `Manager 로 동시에 두 번 로그인하면 두 번째는 BUSY 이고 계정 선택은 한 번만 뜬다`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        gateway.credential = {
            gate.await()
            googleCredential()
        }
        val manager = SocialLoginManager.builder<Activity>().register(provider()).build()
        val first = async { manager.signIn(ProviderId.GOOGLE, activity) }
        assertEquals(FailureKind.BUSY, manager.signIn(ProviderId.GOOGLE, activity).failure().kind)
        gate.complete(Unit)
        assertTrue(first.await() is SocialLoginResult.Success)
        assertEquals(1, gateway.calls.size)
        // Manager 는 선언하지 않은 동작을 부르지 않는다
        assertEquals(FailureKind.UNSUPPORTED, manager.unlink(ProviderId.GOOGLE).failure().kind)
    }

    @Test
    fun `프로세스 재시작 — 새 Provider 도 같은 설정으로 로그인한다`() = runTest(dispatcher) {
        provider().signIn(activity, SignInOptions.DEFAULT)
        val restarted = GoogleLoginProvider(GoogleLoginConfig(CLIENT_ID), gateway, dispatcher)
        assertTrue(restarted.signIn(activity, SignInOptions.DEFAULT) is SocialLoginResult.Success)
        assertEquals(2, gateway.options.size)
    }
}

@RunWith(RobolectricTestRunner::class)
class GoogleCredentialsTest {

    @Test
    fun `빈 값은 null 로 옮긴다`() {
        val acc = (GoogleCredentials.toResult(googleCredential(displayName = " ", picture = null)) as SocialLoginResult.Success).value
        assertNull(acc.displayName)
        assertNull(acc.profileImageUrl)
        val emptyName = (GoogleCredentials.toResult(googleCredential(displayName = null)) as SocialLoginResult.Success).value
        assertNull(emptyName.displayName)
        val blankId = (GoogleCredentials.toResult(googleCredential(id = " ", picture = "")) as SocialLoginResult.Success).value
        assertNull(blankId.email)
        assertNull(blankId.profileImageUrl)
    }

    @Test
    fun `공백뿐인 ID 토큰은 UNKNOWN`() {
        val f = GoogleCredentials.toResult(googleCredential(idToken = " ")) as SocialLoginResult.Failure
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertEquals(ProviderId.GOOGLE, f.providerId)
        assertTrue(f.message.contains("ID 토큰"))
    }

    @Test
    fun `toString 에 토큰과 이메일이 나오지 않는다`() {
        val s = GoogleCredentials.toResult(googleCredential()).toString()
        assertFalse(s.contains("person@example.com"))
        assertFalse(s.contains("header.payload.sig"))
    }

    @Test
    fun `옵션 — 시트 선택지와 nonce`() {
        val sheet = GoogleOptions.build(
            GoogleLoginConfig(CLIENT_ID, filterByAuthorizedAccounts = true, autoSelectEnabled = true), "n",
        ) as GetGoogleIdOption
        assertTrue(sheet.filterByAuthorizedAccounts)
        assertTrue(sheet.autoSelectEnabled)
        assertEquals("n", sheet.nonce)
        val plain = GoogleOptions.build(GoogleLoginConfig(CLIENT_ID), null) as GetGoogleIdOption
        assertFalse(plain.filterByAuthorizedAccounts)
        assertFalse(plain.autoSelectEnabled)
        assertNull(plain.nonce)
        val button = GoogleOptions.build(GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BUTTON), "m") as GetSignInWithGoogleOption
        assertEquals("m", button.nonce)
    }
}
