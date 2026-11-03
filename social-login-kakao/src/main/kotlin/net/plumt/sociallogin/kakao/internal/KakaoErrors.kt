package net.plumt.sociallogin.kakao.internal

import com.kakao.sdk.common.model.ApiError
import com.kakao.sdk.common.model.ApiErrorCause
import com.kakao.sdk.common.model.AuthError
import com.kakao.sdk.common.model.AuthErrorCause
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginErrors
import net.plumt.sociallogin.SocialLoginResult

/**
 * 카카오 SDK 오류를 결과로 바꾼다.
 *
 * 각 오류 원인 열거를 `else` 없이 전부 적는다. SDK 를 올려 원인이 늘면 컴파일이 깨져 분류를 빠뜨리지 않는다.
 * 메시지에는 SDK 의 원래 문구를 넣지 않는다(서버 응답이 섞일 수 있다). 원래 예외는 `cause` 로 남는다.
 */
internal object KakaoErrors {

    private val ID = ProviderId.KAKAO

    /** 카카오톡 로그인에서 사용자가 취소했는지. 취소면 웹 로그인으로 넘어가지 않는다. */
    fun isUserCancel(error: Throwable): Boolean =
        error is ClientError && error.reason == ClientErrorCause.Cancelled

    fun toResult(error: Throwable, operation: String): SocialLoginResult<Nothing> = when (error) {
        is ClientError ->
            if (error.reason == ClientErrorCause.Cancelled) {
                SocialLoginResult.Cancelled
            } else {
                failure(kindOf(error.reason), operation, "ClientError.${error.reason.name}", error)
            }
        is AuthError -> failure(kindOf(error.reason), operation, "AuthError.${error.reason.name}(${error.statusCode})", error)
        is ApiError -> failure(kindOf(error.reason), operation, "ApiError.${error.reason.name}(${error.reason.errorCode})", error)
        else -> SocialLoginErrors.failureOf(
            throwable = error,
            message = "카카오 $operation 실패: ${error.javaClass.name}",
            providerId = ID,
        )
    }

    fun kindOf(cause: ClientErrorCause): FailureKind = when (cause) {
        // Cancelled 는 toResult 가 먼저 Cancelled 결과로 바꾼다. 여기 오는 것은 분류만 묻는 경우다
        ClientErrorCause.Cancelled -> FailureKind.UNKNOWN
        ClientErrorCause.TokenNotFound -> FailureKind.NOT_SIGNED_IN
        ClientErrorCause.NotSupported -> FailureKind.UNSUPPORTED
        ClientErrorCause.BadParameter -> FailureKind.CONFIGURATION
        ClientErrorCause.IllegalState -> FailureKind.CONFIGURATION
        ClientErrorCause.Unknown -> FailureKind.UNKNOWN
    }

    fun kindOf(cause: AuthErrorCause): FailureKind = when (cause) {
        AuthErrorCause.InvalidRequest -> FailureKind.CONFIGURATION
        AuthErrorCause.InvalidClient -> FailureKind.CONFIGURATION
        AuthErrorCause.InvalidScope -> FailureKind.CONFIGURATION
        AuthErrorCause.InvalidGrant -> FailureKind.AUTHENTICATION
        AuthErrorCause.Misconfigured -> FailureKind.CONFIGURATION
        AuthErrorCause.Unauthorized -> FailureKind.CONFIGURATION
        AuthErrorCause.AccessDenied -> FailureKind.ACCESS_DENIED
        AuthErrorCause.ServerError -> FailureKind.PROVIDER_SERVER
        AuthErrorCause.Unknown -> FailureKind.UNKNOWN
    }

    fun kindOf(cause: ApiErrorCause): FailureKind = when (cause) {
        ApiErrorCause.InternalError -> FailureKind.PROVIDER_SERVER
        ApiErrorCause.IllegalParams -> FailureKind.CONFIGURATION
        ApiErrorCause.UnsupportedApi -> FailureKind.UNSUPPORTED
        ApiErrorCause.BlockedAction -> FailureKind.CONFIGURATION
        ApiErrorCause.PermissionDenied -> FailureKind.CONFIGURATION
        ApiErrorCause.DeprecatedApi -> FailureKind.UNSUPPORTED
        ApiErrorCause.ApiLimitExceeded -> FailureKind.PROVIDER_SERVER
        ApiErrorCause.NotRegisteredUser -> FailureKind.NOT_SIGNED_IN
        ApiErrorCause.AlreadyRegisteredUser -> FailureKind.UNKNOWN
        ApiErrorCause.AccountDoesNotExist -> FailureKind.AUTHENTICATION
        ApiErrorCause.PropertyKeyDoesNotExist -> FailureKind.CONFIGURATION
        ApiErrorCause.AppDoesNotExist -> FailureKind.CONFIGURATION
        ApiErrorCause.InvalidToken -> FailureKind.AUTHENTICATION
        ApiErrorCause.InsufficientScope -> FailureKind.ACCESS_DENIED
        ApiErrorCause.RequiredAgeVerification -> FailureKind.ACCESS_DENIED
        ApiErrorCause.UnderAgeLimit -> FailureKind.ACCESS_DENIED
        ApiErrorCause.SigningIsNotCompleted -> FailureKind.UNKNOWN
        ApiErrorCause.InvalidTransaction -> FailureKind.UNKNOWN
        ApiErrorCause.TransactionHasExpired -> FailureKind.UNKNOWN
        ApiErrorCause.NotTalkUser -> FailureKind.UNSUPPORTED
        ApiErrorCause.NotFriend -> FailureKind.UNKNOWN
        ApiErrorCause.UserDeviceUnsupported -> FailureKind.UNSUPPORTED
        ApiErrorCause.TalkMessageDisabled -> FailureKind.UNKNOWN
        ApiErrorCause.TalkSendMessageMonthlyLimitExceed -> FailureKind.UNKNOWN
        ApiErrorCause.TalkSendMessageDailyLimitExceed -> FailureKind.UNKNOWN
        ApiErrorCause.ImageUploadSizeExceeded -> FailureKind.UNKNOWN
        ApiErrorCause.ServerTimeOut -> FailureKind.PROVIDER_SERVER
        ApiErrorCause.ImageMaxUploadCountExceed -> FailureKind.UNKNOWN
        ApiErrorCause.DeveloperDoesNotExist -> FailureKind.CONFIGURATION
        ApiErrorCause.UnderMaintenance -> FailureKind.PROVIDER_SERVER
        ApiErrorCause.Unknown -> FailureKind.UNKNOWN
    }

    private fun failure(kind: FailureKind, operation: String, vendorCode: String, cause: Throwable) =
        SocialLoginResult.Failure(
            kind = kind,
            message = "카카오 $operation 실패: $vendorCode",
            providerId = ID,
            vendorCode = vendorCode,
            cause = cause,
        )
}
