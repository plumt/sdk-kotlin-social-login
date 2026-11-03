package net.plumt.sociallogin.naver.internal

import com.navercorp.nid.oauth.NidOAuthErrorCode
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginErrors
import net.plumt.sociallogin.SocialLoginResult

/**
 * 네이버 SDK 오류를 결과로 바꾼다.
 *
 * 분류는 콜백 문구가 아니라 SDK 오류 코드([NidOAuthErrorCode])로 한다. 열거를 `else` 없이 전부 적어,
 * SDK 를 올려 코드가 늘면 컴파일이 깨진다.
 */
internal object NaverErrors {

    private val ID = ProviderId.NAVER

    fun toResult(error: Throwable, operation: String): SocialLoginResult<Nothing> {
        if (error !is NaverSdkException) {
            return SocialLoginErrors.failureOf(
                throwable = error,
                message = "네이버 $operation 실패: ${error.javaClass.name}",
                providerId = ID,
            )
        }
        if (error.code == NidOAuthErrorCode.CLIENT_USER_CANCEL) return SocialLoginResult.Cancelled
        val vendorCode = buildString {
            append(error.code.name)
            error.httpStatus?.let { append("(HTTP ").append(it).append(')') }
            error.callbackErrorCode?.let { append("(error ").append(it).append(')') }
        }
        return SocialLoginResult.Failure(
            kind = kindOf(error.code, error.httpStatus),
            message = "네이버 $operation 실패: $vendorCode",
            providerId = ID,
            vendorCode = vendorCode,
            cause = error,
        )
    }

    fun kindOf(code: NidOAuthErrorCode, httpStatus: Int?): FailureKind = when (code) {
        // 코드가 없거나 분류되지 않았으면 HTTP 상태로 가른다
        NidOAuthErrorCode.NONE, NidOAuthErrorCode.ERROR_NO_CATAGORIZED -> when (httpStatus) {
            null -> FailureKind.UNKNOWN
            401 -> FailureKind.AUTHENTICATION
            in 500..599 -> FailureKind.PROVIDER_SERVER
            else -> FailureKind.UNKNOWN
        }
        NidOAuthErrorCode.SERVER_ERROR_INVALID_REQUEST -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.SERVER_ERROR_UNAUTHORIZED_CLIENT -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.SERVER_ERROR_ACCESS_DENIED -> FailureKind.ACCESS_DENIED
        NidOAuthErrorCode.SERVER_ERROR_UNSUPPORTED_RESPONSE_TYPE -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.SERVER_ERROR_INVALID_SCOPE -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.SERVER_ERROR_SERVER_ERROR -> FailureKind.PROVIDER_SERVER
        NidOAuthErrorCode.SERVER_ERROR_TEMPORARILY_UNAVAILABLE -> FailureKind.PROVIDER_SERVER
        NidOAuthErrorCode.CLIENT_ERROR_PARSING_FAIL -> FailureKind.UNKNOWN
        NidOAuthErrorCode.CLIENT_ERROR_NO_CLIENTID -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.CLIENT_ERROR_NO_CLIENTSECRET -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.CLIENT_ERROR_NO_CLIENTNAME -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.CLIENT_ERROR_NO_CALLBACKURL -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.CLIENT_ERROR_CONNECTION_ERROR -> FailureKind.NETWORK
        NidOAuthErrorCode.CLIENT_ERROR_CERTIFICATION_ERROR -> FailureKind.NETWORK
        // toResult 가 먼저 Cancelled 결과로 바꾼다. 여기 오는 것은 분류만 묻는 경우다
        NidOAuthErrorCode.CLIENT_USER_CANCEL -> FailureKind.UNKNOWN
        NidOAuthErrorCode.ACTIVITY_IS_SINGLE_TASK -> FailureKind.CONFIGURATION
        NidOAuthErrorCode.WEB_VIEW_IS_DEPRECATED -> FailureKind.UNSUPPORTED
        NidOAuthErrorCode.NO_APP_FOR_AUTHENTICATION -> FailureKind.UNSUPPORTED
        NidOAuthErrorCode.SDK_IS_NOT_INITIALIZED -> FailureKind.CONFIGURATION
    }
}
