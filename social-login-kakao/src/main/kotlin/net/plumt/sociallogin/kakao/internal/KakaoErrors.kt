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
 * 분류는 원인 열거의 **이름**으로 한다. 벤더 SDK 는 `implementation` 이라 앱이 더 새 버전을 고를 수 있다. 그때 이 모듈이
 * 모르는 원인이 오거나 아는 원인이 빠져도, 이름 표에 없으면 [FailureKind.UNKNOWN] 으로 떨어질 뿐 예외
 * (`NoWhenBranchMatchedException`·`NoSuchFieldError`)가 나지 않는다. 빠뜨린 원인은 테스트가 열거 전체를 대조해 잡는다.
 *
 * 메시지에는 SDK 의 원래 문구를 넣지 않는다(서버 응답이 섞일 수 있다). 원래 예외는 `cause` 로 남는다.
 */
internal object KakaoErrors {

    private val ID = ProviderId.KAKAO

    /** 사용자 취소 원인의 이름. 상수 필드를 직접 읽지 않아 SDK 버전이 달라도 링크 오류가 나지 않는다. */
    private const val CANCELLED = "Cancelled"
    private const val ACCESS_DENIED = "AccessDenied"
    private const val SIGN_IN = "signIn"

    /** [ClientErrorCause] 이름 → 갈래. `Cancelled` 는 [toResult] 가 먼저 [SocialLoginResult.Cancelled] 로 바꾼다. */
    internal val CLIENT: Map<String, FailureKind> = mapOf(
        CANCELLED to FailureKind.UNKNOWN,
        "TokenNotFound" to FailureKind.NOT_SIGNED_IN,
        "NotSupported" to FailureKind.UNSUPPORTED,
        "BadParameter" to FailureKind.CONFIGURATION,
        "IllegalState" to FailureKind.CONFIGURATION,
        "Unknown" to FailureKind.UNKNOWN,
    )

    /** [AuthErrorCause] 이름 → 갈래. */
    internal val AUTH: Map<String, FailureKind> = mapOf(
        "InvalidRequest" to FailureKind.CONFIGURATION,
        "InvalidClient" to FailureKind.CONFIGURATION,
        "InvalidScope" to FailureKind.CONFIGURATION,
        "InvalidGrant" to FailureKind.AUTHENTICATION,
        "Misconfigured" to FailureKind.CONFIGURATION,
        "Unauthorized" to FailureKind.CONFIGURATION,
        "AccessDenied" to FailureKind.ACCESS_DENIED,
        "ServerError" to FailureKind.PROVIDER_SERVER,
        "Unknown" to FailureKind.UNKNOWN,
    )

    /** [ApiErrorCause] 이름 → 갈래. */
    internal val API: Map<String, FailureKind> = mapOf(
        "InternalError" to FailureKind.PROVIDER_SERVER,
        "IllegalParams" to FailureKind.CONFIGURATION,
        "UnsupportedApi" to FailureKind.UNSUPPORTED,
        "BlockedAction" to FailureKind.CONFIGURATION,
        "PermissionDenied" to FailureKind.CONFIGURATION,
        "DeprecatedApi" to FailureKind.UNSUPPORTED,
        "ApiLimitExceeded" to FailureKind.PROVIDER_SERVER,
        "NotRegisteredUser" to FailureKind.NOT_SIGNED_IN,
        "AlreadyRegisteredUser" to FailureKind.UNKNOWN,
        "AccountDoesNotExist" to FailureKind.AUTHENTICATION,
        "PropertyKeyDoesNotExist" to FailureKind.CONFIGURATION,
        "AppDoesNotExist" to FailureKind.CONFIGURATION,
        "InvalidToken" to FailureKind.AUTHENTICATION,
        "InsufficientScope" to FailureKind.ACCESS_DENIED,
        "RequiredAgeVerification" to FailureKind.ACCESS_DENIED,
        "UnderAgeLimit" to FailureKind.ACCESS_DENIED,
        "SigningIsNotCompleted" to FailureKind.UNKNOWN,
        "InvalidTransaction" to FailureKind.UNKNOWN,
        "TransactionHasExpired" to FailureKind.UNKNOWN,
        "NotTalkUser" to FailureKind.UNSUPPORTED,
        "NotFriend" to FailureKind.UNKNOWN,
        "UserDeviceUnsupported" to FailureKind.UNSUPPORTED,
        "TalkMessageDisabled" to FailureKind.UNKNOWN,
        "TalkSendMessageMonthlyLimitExceed" to FailureKind.UNKNOWN,
        "TalkSendMessageDailyLimitExceed" to FailureKind.UNKNOWN,
        "ImageUploadSizeExceeded" to FailureKind.UNKNOWN,
        "ServerTimeOut" to FailureKind.PROVIDER_SERVER,
        "ImageMaxUploadCountExceed" to FailureKind.UNKNOWN,
        "DeveloperDoesNotExist" to FailureKind.CONFIGURATION,
        "UnderMaintenance" to FailureKind.PROVIDER_SERVER,
        "Unknown" to FailureKind.UNKNOWN,
    )

    /**
     * 로그인에서 사용자가 취소했는지. 취소면 웹 로그인으로 넘어가지 않는다.
     * 로그인 화면을 닫으면 `ClientError(Cancelled)`, 동의 화면에서 "취소"를 누르면 `AuthError(AccessDenied)` 가 온다.
     */
    fun isUserCancel(error: Throwable): Boolean =
        (error is ClientError && error.reason.name == CANCELLED) ||
            (error is AuthError && error.reason.name == ACCESS_DENIED)

    fun toResult(error: Throwable, operation: String): SocialLoginResult<Nothing> = when (error) {
        is ClientError ->
            if (error.reason.name == CANCELLED) {
                SocialLoginResult.Cancelled
            } else {
                failure(kindOf(error.reason), operation, "ClientError.${error.reason.name}", error)
            }
        is AuthError ->
            if (operation == SIGN_IN && error.reason.name == ACCESS_DENIED) {
                // 로그인 동의 화면의 "취소" — 사용자가 고른 결과라 실패로 알리지 않는다
                SocialLoginResult.Cancelled
            } else {
                failure(kindOf(error.reason), operation, "AuthError.${error.reason.name}(${error.statusCode})", error)
            }
        is ApiError -> failure(kindOf(error.reason), operation, "ApiError.${error.reason.name}(${error.reason.errorCode})", error)
        else -> SocialLoginErrors.failureOf(
            throwable = error,
            message = "카카오 $operation 실패: ${error.javaClass.name}",
            providerId = ID,
        )
    }

    fun kindOf(cause: ClientErrorCause): FailureKind = kindOfClient(cause.name)

    fun kindOf(cause: AuthErrorCause): FailureKind = kindOfAuth(cause.name)

    fun kindOf(cause: ApiErrorCause): FailureKind = kindOfApi(cause.name)

    /** 표에 없는 이름(이 모듈보다 새 SDK 의 원인)은 [FailureKind.UNKNOWN]. vendorCode 에는 이름이 그대로 남는다. */
    fun kindOfClient(name: String): FailureKind = CLIENT[name] ?: FailureKind.UNKNOWN

    fun kindOfAuth(name: String): FailureKind = AUTH[name] ?: FailureKind.UNKNOWN

    fun kindOfApi(name: String): FailureKind = API[name] ?: FailureKind.UNKNOWN

    private fun failure(kind: FailureKind, operation: String, vendorCode: String, cause: Throwable) =
        SocialLoginResult.Failure(
            kind = kind,
            message = "카카오 $operation 실패: $vendorCode",
            providerId = ID,
            vendorCode = vendorCode,
            cause = cause,
        )
}
