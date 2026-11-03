package net.plumt.sociallogin.kakao

import com.kakao.sdk.common.model.ApiError
import com.kakao.sdk.common.model.ApiErrorCause
import com.kakao.sdk.common.model.ApiErrorResponse
import com.kakao.sdk.common.model.AuthError
import com.kakao.sdk.common.model.AuthErrorCause
import com.kakao.sdk.common.model.AuthErrorResponse
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.FailureKind.ACCESS_DENIED
import net.plumt.sociallogin.FailureKind.AUTHENTICATION
import net.plumt.sociallogin.FailureKind.CONFIGURATION
import net.plumt.sociallogin.FailureKind.NOT_SIGNED_IN
import net.plumt.sociallogin.FailureKind.PROVIDER_SERVER
import net.plumt.sociallogin.FailureKind.UNKNOWN
import net.plumt.sociallogin.FailureKind.UNSUPPORTED
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.kakao.internal.KakaoErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class KakaoErrorsTest {

    private val clientTable = mapOf(
        ClientErrorCause.Cancelled to UNKNOWN,
        ClientErrorCause.TokenNotFound to NOT_SIGNED_IN,
        ClientErrorCause.NotSupported to UNSUPPORTED,
        ClientErrorCause.BadParameter to CONFIGURATION,
        ClientErrorCause.IllegalState to CONFIGURATION,
        ClientErrorCause.Unknown to UNKNOWN,
    )

    private val authTable = mapOf(
        AuthErrorCause.InvalidRequest to CONFIGURATION,
        AuthErrorCause.InvalidClient to CONFIGURATION,
        AuthErrorCause.InvalidScope to CONFIGURATION,
        AuthErrorCause.InvalidGrant to AUTHENTICATION,
        AuthErrorCause.Misconfigured to CONFIGURATION,
        AuthErrorCause.Unauthorized to CONFIGURATION,
        AuthErrorCause.AccessDenied to ACCESS_DENIED,
        AuthErrorCause.ServerError to PROVIDER_SERVER,
        AuthErrorCause.Unknown to UNKNOWN,
    )

    private val apiTable = mapOf(
        ApiErrorCause.InternalError to PROVIDER_SERVER,
        ApiErrorCause.IllegalParams to CONFIGURATION,
        ApiErrorCause.UnsupportedApi to UNSUPPORTED,
        ApiErrorCause.BlockedAction to CONFIGURATION,
        ApiErrorCause.PermissionDenied to CONFIGURATION,
        ApiErrorCause.DeprecatedApi to UNSUPPORTED,
        ApiErrorCause.ApiLimitExceeded to PROVIDER_SERVER,
        ApiErrorCause.NotRegisteredUser to NOT_SIGNED_IN,
        ApiErrorCause.AlreadyRegisteredUser to UNKNOWN,
        ApiErrorCause.AccountDoesNotExist to AUTHENTICATION,
        ApiErrorCause.PropertyKeyDoesNotExist to CONFIGURATION,
        ApiErrorCause.AppDoesNotExist to CONFIGURATION,
        ApiErrorCause.InvalidToken to AUTHENTICATION,
        ApiErrorCause.InsufficientScope to ACCESS_DENIED,
        ApiErrorCause.RequiredAgeVerification to ACCESS_DENIED,
        ApiErrorCause.UnderAgeLimit to ACCESS_DENIED,
        ApiErrorCause.SigningIsNotCompleted to UNKNOWN,
        ApiErrorCause.InvalidTransaction to UNKNOWN,
        ApiErrorCause.TransactionHasExpired to UNKNOWN,
        ApiErrorCause.NotTalkUser to UNSUPPORTED,
        ApiErrorCause.NotFriend to UNKNOWN,
        ApiErrorCause.UserDeviceUnsupported to UNSUPPORTED,
        ApiErrorCause.TalkMessageDisabled to UNKNOWN,
        ApiErrorCause.TalkSendMessageMonthlyLimitExceed to UNKNOWN,
        ApiErrorCause.TalkSendMessageDailyLimitExceed to UNKNOWN,
        ApiErrorCause.ImageUploadSizeExceeded to UNKNOWN,
        ApiErrorCause.ServerTimeOut to PROVIDER_SERVER,
        ApiErrorCause.ImageMaxUploadCountExceed to UNKNOWN,
        ApiErrorCause.DeveloperDoesNotExist to CONFIGURATION,
        ApiErrorCause.UnderMaintenance to PROVIDER_SERVER,
        ApiErrorCause.Unknown to UNKNOWN,
    )

    private fun authError(cause: AuthErrorCause, status: Int = 400) =
        AuthError(status, cause, AuthErrorResponse("err", "server says something"))

    private fun apiError(cause: ApiErrorCause) =
        ApiError(400, cause, ApiErrorResponse(cause.errorCode, "server says something", null, null, null))

    @Test
    fun `표가 SDK 의 모든 원인을 덮는다`() {
        assertEquals(ClientErrorCause.entries.toSet(), clientTable.keys)
        assertEquals(AuthErrorCause.entries.toSet(), authTable.keys)
        assertEquals(ApiErrorCause.entries.toSet(), apiTable.keys)
    }

    @Test
    fun `ClientError 원인별 분류`() {
        clientTable.forEach { (cause, kind) -> assertEquals(cause.name, kind, KakaoErrors.kindOf(cause)) }
        ClientErrorCause.entries.filter { it != ClientErrorCause.Cancelled }.forEach { cause ->
            val error = ClientError(cause, "msg")
            val f = KakaoErrors.toResult(error, "signIn") as SocialLoginResult.Failure
            assertEquals(clientTable[cause], f.kind)
            assertEquals("ClientError.${cause.name}", f.vendorCode)
            assertEquals("카카오 signIn 실패: ClientError.${cause.name}", f.message)
            assertEquals(ProviderId.KAKAO, f.providerId)
            assertSame(error, f.cause)
        }
    }

    @Test
    fun `ClientError Cancelled 는 Cancelled 결과`() {
        assertSame(SocialLoginResult.Cancelled, KakaoErrors.toResult(ClientError(ClientErrorCause.Cancelled, "x"), "signIn"))
        assertTrue(KakaoErrors.isUserCancel(ClientError(ClientErrorCause.Cancelled, "x")))
        assertFalse(KakaoErrors.isUserCancel(ClientError(ClientErrorCause.Unknown, "x")))
        assertFalse(KakaoErrors.isUserCancel(authError(AuthErrorCause.AccessDenied)))
        assertFalse(KakaoErrors.isUserCancel(RuntimeException()))
    }

    @Test
    fun `AuthError 원인별 분류와 상태 코드`() {
        AuthErrorCause.entries.forEach { cause ->
            assertEquals(cause.name, authTable[cause], KakaoErrors.kindOf(cause))
            val error = authError(cause, status = 401)
            val f = KakaoErrors.toResult(error, "refreshToken") as SocialLoginResult.Failure
            assertEquals(authTable[cause], f.kind)
            assertEquals("AuthError.${cause.name}(401)", f.vendorCode)
            assertFalse(f.message.contains("server says"))
            assertSame(error, f.cause)
        }
    }

    @Test
    fun `ApiError 원인별 분류와 오류 코드`() {
        ApiErrorCause.entries.forEach { cause ->
            assertEquals(cause.name, apiTable[cause], KakaoErrors.kindOf(cause))
            val error = apiError(cause)
            val f = KakaoErrors.toResult(error, "unlink") as SocialLoginResult.Failure
            assertEquals(apiTable[cause], f.kind)
            assertEquals("ApiError.${cause.name}(${cause.errorCode})", f.vendorCode)
            assertFalse(f.message.contains("server says"))
            assertSame(error, f.cause)
        }
        assertEquals("ApiError.InvalidToken(-401)", (KakaoErrors.toResult(apiError(ApiErrorCause.InvalidToken), "x") as SocialLoginResult.Failure).vendorCode)
    }

    @Test
    fun `SDK 밖 예외는 공통 분류`() {
        val io = UnknownHostException("h")
        val f = KakaoErrors.toResult(io, "signIn") as SocialLoginResult.Failure
        assertEquals(FailureKind.NETWORK, f.kind)
        assertSame(io, f.cause)
        assertEquals(null, f.vendorCode)
        assertTrue(f.message.contains("java.net.UnknownHostException"))
        val wrapped = RuntimeException(IOException())
        assertEquals(FailureKind.NETWORK, (KakaoErrors.toResult(wrapped, "x") as SocialLoginResult.Failure).kind)
        assertEquals(UNKNOWN, (KakaoErrors.toResult(IllegalStateException(), "x") as SocialLoginResult.Failure).kind)
    }

    @Test
    fun `무작위 오류에도 분류는 값만 돌려준다`() {
        val random = Random(123)
        repeat(2_000) {
            val error: Throwable = when (random.nextInt(5)) {
                0 -> ClientError(ClientErrorCause.entries.random(random), "m")
                1 -> authError(AuthErrorCause.entries.random(random), random.nextInt(-1, 600))
                2 -> apiError(ApiErrorCause.entries.random(random))
                3 -> IOException()
                else -> RuntimeException()
            }
            val r = KakaoErrors.toResult(error, "op")
            assertTrue(r is SocialLoginResult.Failure || r === SocialLoginResult.Cancelled)
        }
    }
}
