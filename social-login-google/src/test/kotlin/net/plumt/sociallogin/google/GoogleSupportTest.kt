package net.plumt.sociallogin.google

import android.app.Activity
import android.app.Application
import androidx.credentials.exceptions.ClearCredentialCustomException
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.ClearCredentialInterruptedException
import androidx.credentials.exceptions.ClearCredentialProviderConfigurationException
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.exceptions.ClearCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.google.internal.GoogleErrors
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
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class GoogleErrorsTest {

    private val getTable: List<Pair<GetCredentialException, FailureKind>> = listOf(
        GetCredentialCancellationException("m") to FailureKind.UNKNOWN,
        NoCredentialException("m") to FailureKind.NO_CREDENTIAL,
        GetCredentialInterruptedException("m") to FailureKind.INTERRUPTED,
        GetCredentialProviderConfigurationException("m") to FailureKind.CONFIGURATION,
        GetCredentialUnsupportedException("m") to FailureKind.UNSUPPORTED,
        GetCredentialUnknownException("m") to FailureKind.UNKNOWN,
        GetCredentialCustomException("com.example.custom", "m") to FailureKind.UNKNOWN,
    )

    private val clearTable: List<Pair<ClearCredentialException, FailureKind>> = listOf(
        ClearCredentialInterruptedException("m") to FailureKind.INTERRUPTED,
        ClearCredentialProviderConfigurationException("m") to FailureKind.CONFIGURATION,
        ClearCredentialUnsupportedException("m") to FailureKind.UNSUPPORTED,
        ClearCredentialUnknownException("m") to FailureKind.UNKNOWN,
        ClearCredentialCustomException("com.example.custom", "m") to FailureKind.UNKNOWN,
    )

    @Test
    fun `Credential Manager 조회 예외별 분류`() {
        getTable.forEach { (e, kind) -> assertEquals(e.javaClass.simpleName, kind, GoogleErrors.kindOf(e)) }
        getTable.filter { it.first !is GetCredentialCancellationException }.forEach { (e, kind) ->
            val f = GoogleErrors.toResult(e, "signIn") as SocialLoginResult.Failure
            assertEquals(kind, f.kind)
            assertEquals(e.type, f.vendorCode)
            // 계정 없음은 설정 오류일 수도 있다는 안내가 붙는다(0.0.2)
            assertTrue(f.message, f.message.startsWith("Google signIn 실패: ${e.type}"))
            assertEquals(ProviderId.GOOGLE, f.providerId)
            assertSame(e, f.cause)
        }
        assertEquals("com.example.custom", (GoogleErrors.toResult(GetCredentialCustomException("com.example.custom", null), "x") as SocialLoginResult.Failure).vendorCode)
    }

    @Test
    fun `사용자 취소는 Cancelled`() {
        assertSame(SocialLoginResult.Cancelled, GoogleErrors.toResult(GetCredentialCancellationException(), "signIn"))
    }

    @Test
    fun `자격 증명 지우기 예외별 분류`() {
        clearTable.forEach { (e, kind) ->
            assertEquals(e.javaClass.simpleName, kind, GoogleErrors.kindOf(e))
            val f = GoogleErrors.toResult(e, "signOut") as SocialLoginResult.Failure
            assertEquals(kind, f.kind)
            assertEquals(e.type, f.vendorCode)
            assertSame(e, f.cause)
        }
    }

    @Test
    fun `ID 토큰 해석 실패와 그 밖의 예외`() {
        val parse = GoogleIdTokenParsingException(IllegalArgumentException())
        val f = GoogleErrors.toResult(parse, "signIn") as SocialLoginResult.Failure
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertEquals("GoogleIdTokenParsingException", f.vendorCode)
        assertSame(parse, f.cause)
        assertEquals(FailureKind.NETWORK, (GoogleErrors.toResult(IOException(), "x") as SocialLoginResult.Failure).kind)
        val other = GoogleErrors.toResult(IllegalStateException(), "x") as SocialLoginResult.Failure
        assertEquals(FailureKind.UNKNOWN, other.kind)
        assertEquals(null, other.vendorCode)
        assertTrue(other.message.contains("java.lang.IllegalStateException"))
    }

    @Test
    fun `예외 문구는 결과 메시지에 들어가지 않는다`() {
        val f = GoogleErrors.toResult(NoCredentialException("person@example.com"), "signIn") as SocialLoginResult.Failure
        assertFalse(f.message.contains("person@example.com"))
    }

    @Test
    fun `무작위 예외에도 분류는 값만 돌려준다`() {
        val random = Random(3)
        val pool = getTable.map { it.first } + clearTable.map { it.first } +
            listOf(IOException(), RuntimeException(), GoogleIdTokenParsingException(IllegalArgumentException()))
        repeat(2_000) {
            val r = GoogleErrors.toResult(pool[random.nextInt(pool.size)], "op")
            assertTrue(r is SocialLoginResult.Failure || r === SocialLoginResult.Cancelled)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class GoogleLoginConfigTest {

    @Test
    fun `기본값과 경계`() {
        val c = GoogleLoginConfig(CLIENT_ID)
        // 0.0.2 부터 기본은 Google Sign-In 계정 선택이다
        assertEquals(GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN, c.mode)
        assertFalse(c.filterByAuthorizedAccounts)
        assertFalse(c.autoSelectEnabled)
        GoogleLoginConfig("a" + GoogleLoginConfig.CLIENT_ID_SUFFIX)
        GoogleLoginConfig("a".repeat(GoogleLoginConfig.MAX_CLIENT_ID_LENGTH - GoogleLoginConfig.CLIENT_ID_SUFFIX.length) + GoogleLoginConfig.CLIENT_ID_SUFFIX)
        assertEquals(256, GoogleLoginConfig.MAX_CLIENT_ID_LENGTH)
        GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BUTTON)
    }

    @Test
    fun `틀린 클라이언트 ID 는 길이만 알리고 거절`() {
        val tooLong = "a".repeat(GoogleLoginConfig.MAX_CLIENT_ID_LENGTH - GoogleLoginConfig.CLIENT_ID_SUFFIX.length + 1) +
            GoogleLoginConfig.CLIENT_ID_SUFFIX
        listOf(
            GoogleLoginConfig.CLIENT_ID_SUFFIX, "1234-abc", "1234 abc.apps.googleusercontent.com",
            "YOUR_GOOGLE_WEB_CLIENT_ID", tooLong, "가.apps.googleusercontent.com\n",
        ).forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) { GoogleLoginConfig(bad) }
            assertTrue(e.message!!.contains("받은 길이 ${bad.length}"))
            if (bad.length > 30) assertFalse(e.message!!.contains(bad))
        }
    }

    @Test
    fun `시트가 아닌 모드에 시트 전용 선택지를 켜면 거절`() {
        for (mode in listOf(GoogleSignInMode.BUTTON, GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN)) {
            listOf(true to false, false to true, true to true).forEach { (filter, auto) ->
                val e = assertThrows(IllegalArgumentException::class.java) {
                    GoogleLoginConfig(CLIENT_ID, mode, filter, auto)
                }
                assertTrue(e.message!!.contains("BOTTOM_SHEET"))
                assertTrue(e.message!!.contains("mode=$mode"))
                val x = assertThrows(IllegalArgumentException::class.java) { GoogleSignInExtension(mode, filter, auto) }
                assertTrue(x.message!!.contains("mode=$mode"))
            }
        }
    }

    @Test
    fun `toString 은 클라이언트 ID 를 가린다, equals 는 모든 필드를 본다`() {
        val c = GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BOTTOM_SHEET, true, false)
        assertFalse(c.toString().contains(CLIENT_ID))
        assertEquals(c, GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BOTTOM_SHEET, true, false))
        assertEquals(c.hashCode(), GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BOTTOM_SHEET, true, false).hashCode())
        assertNotEquals(c, GoogleLoginConfig("x$CLIENT_ID", GoogleSignInMode.BOTTOM_SHEET, true, false))
        assertNotEquals(c, GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BUTTON))
        assertNotEquals(c, GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BOTTOM_SHEET, false, false))
        assertNotEquals(c, GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BOTTOM_SHEET, true, true))
        assertNotEquals(c as Any, CLIENT_ID)
        // 0.0.1 상수의 순서를 지키고 새 상수는 뒤에 붙인다
        assertEquals(listOf("BOTTOM_SHEET", "BUTTON", "LEGACY_GOOGLE_SIGN_IN"), GoogleSignInMode.entries.map { it.name })
    }

    @Test
    fun `무작위 값은 성공하거나 IllegalArgumentException 만`() {
        val random = Random(17)
        val pieces = listOf("a", "1", "-", " ", "가", ".apps.googleusercontent.com", "\n")
        repeat(3_000) {
            val id = buildString { repeat(random.nextInt(0, 12)) { append(pieces[random.nextInt(pieces.size)]) } }
            try {
                GoogleLoginConfig(id, GoogleSignInMode.entries.random(random), random.nextBoolean(), random.nextBoolean())
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 35])
class GoogleLoginFactoryTest {

    @Test
    fun `팩토리는 Provider 를 만든다`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val p = GoogleLogin.provider(app, GoogleLoginConfig(CLIENT_ID))
        assertEquals(ProviderId.GOOGLE, p.id)
        assertSame(ProviderId.GOOGLE, GoogleLogin.PROVIDER_ID)
        assertEquals(setOf(Capability.SIGN_OUT, Capability.UNLINK), p.capabilities)
        val sheet = GoogleLogin.provider(app, GoogleLoginConfig(CLIENT_ID, GoogleSignInMode.BOTTOM_SHEET))
        assertEquals(setOf(Capability.SIGN_OUT), sheet.capabilities)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertEquals(ProviderId.GOOGLE, GoogleLogin.provider(activity, GoogleLoginConfig(CLIENT_ID)).id)
    }
}
