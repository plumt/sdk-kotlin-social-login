package net.plumt.sociallogin.naver.internal

import com.navercorp.nid.oauth.NidOAuthErrorCode
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginErrors
import net.plumt.sociallogin.SocialLoginResult

/**
 * 네이버 SDK 오류를 결과로 바꾼다.
 *
 * 분류는 콜백 문구가 아니라 SDK 오류 코드([NidOAuthErrorCode])의 이름으로 한다. 벤더 SDK 는 `implementation` 이라
 * 앱이 더 새 버전을 고를 수 있어, 모르는 코드가 와도 예외 없이 분류되게 한다. 빠뜨린 코드는 테스트가 열거 전체를 대조해 잡는다.
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
        if (error.code.name == CANCEL) return SocialLoginResult.Cancelled
        // 로그인 동의 화면의 "취소"는 OAuth access_denied 로 온다 — 사용자가 고른 결과라 실패로 알리지 않는다
        if (operation == SIGN_IN && error.code.name == ACCESS_DENIED) return SocialLoginResult.Cancelled
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

    /**
     * 오류 코드 이름 → 갈래. 이름으로 찾아, 이 모듈보다 새 SDK 의 모르는 코드도 예외 없이 HTTP 상태 분류로 떨어진다.
     * `NONE`·`ERROR_NO_CATAGORIZED` 는 표에 없다(HTTP 상태로 가른다). `CLIENT_USER_CANCEL` 은 [toResult] 가 먼저 바꾼다.
     */
    internal val CODES: Map<String, FailureKind> = mapOf(
        "SERVER_ERROR_INVALID_REQUEST" to FailureKind.CONFIGURATION,
        "SERVER_ERROR_UNAUTHORIZED_CLIENT" to FailureKind.CONFIGURATION,
        "SERVER_ERROR_ACCESS_DENIED" to FailureKind.ACCESS_DENIED,
        "SERVER_ERROR_UNSUPPORTED_RESPONSE_TYPE" to FailureKind.CONFIGURATION,
        "SERVER_ERROR_INVALID_SCOPE" to FailureKind.CONFIGURATION,
        "SERVER_ERROR_SERVER_ERROR" to FailureKind.PROVIDER_SERVER,
        "SERVER_ERROR_TEMPORARILY_UNAVAILABLE" to FailureKind.PROVIDER_SERVER,
        "CLIENT_ERROR_PARSING_FAIL" to FailureKind.UNKNOWN,
        "CLIENT_ERROR_NO_CLIENTID" to FailureKind.CONFIGURATION,
        "CLIENT_ERROR_NO_CLIENTSECRET" to FailureKind.CONFIGURATION,
        "CLIENT_ERROR_NO_CLIENTNAME" to FailureKind.CONFIGURATION,
        "CLIENT_ERROR_NO_CALLBACKURL" to FailureKind.CONFIGURATION,
        "CLIENT_ERROR_CONNECTION_ERROR" to FailureKind.NETWORK,
        "CLIENT_ERROR_CERTIFICATION_ERROR" to FailureKind.NETWORK,
        CANCEL to FailureKind.UNKNOWN,
        "ACTIVITY_IS_SINGLE_TASK" to FailureKind.CONFIGURATION,
        "WEB_VIEW_IS_DEPRECATED" to FailureKind.UNSUPPORTED,
        "NO_APP_FOR_AUTHENTICATION" to FailureKind.UNSUPPORTED,
        "SDK_IS_NOT_INITIALIZED" to FailureKind.CONFIGURATION,
    )

    /** HTTP 상태로 가르는 코드 이름. */
    internal val UNCATEGORIZED: Set<String> = setOf("NONE", "ERROR_NO_CATAGORIZED")

    fun kindOf(code: NidOAuthErrorCode, httpStatus: Int?): FailureKind = kindOf(code.name, httpStatus)

    /** 표에 없는 이름(분류 없음·모르는 코드)은 HTTP 상태로 가른다. */
    fun kindOf(codeName: String, httpStatus: Int?): FailureKind = CODES[codeName] ?: kindOfHttp(httpStatus)

    private fun kindOfHttp(httpStatus: Int?): FailureKind = when (httpStatus) {
        null -> FailureKind.UNKNOWN
        401 -> FailureKind.AUTHENTICATION
        in 500..599 -> FailureKind.PROVIDER_SERVER
        else -> FailureKind.UNKNOWN
    }

    /** 사용자 취소 코드의 이름. 상수 필드를 직접 읽지 않아 SDK 버전이 달라도 링크 오류가 나지 않는다. */
    private const val CANCEL = "CLIENT_USER_CANCEL"
    private const val ACCESS_DENIED = "SERVER_ERROR_ACCESS_DENIED"
    private const val SIGN_IN = "signIn"
}
