package net.plumt.sociallogin.google.internal

import android.content.ActivityNotFoundException
import android.os.TransactionTooLargeException
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.ClearCredentialInterruptedException
import androidx.credentials.exceptions.ClearCredentialProviderConfigurationException
import androidx.credentials.exceptions.ClearCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginErrors
import net.plumt.sociallogin.SocialLoginResult

/**
 * Credential Manager·googleid·Google Sign-In 오류를 결과로 바꾼다. 예외 계층이 sealed 가 아니라 모르는 하위 타입은 UNKNOWN 이다.
 * vendorCode 는 Credential Manager 예외의 `type` 문자열(예: `android.credentials.GetCredentialException.TYPE_USER_CANCELED`),
 * Google Sign-In 은 `ApiException(<상태 코드>)` 다.
 */
internal object GoogleErrors {

    private val ID = ProviderId.GOOGLE

    fun toResult(error: Throwable, operation: String): SocialLoginResult<Nothing> = when {
        // 어느 모드든 Binder 한도 초과는 전용 실패로 알린다. 예외 타입이 무엇이든 원인 사슬·문구로 먼저 가린다
        isTransactionTooLarge(error) -> transactionTooLarge(error)
        else -> classify(error, operation)
    }

    private fun classify(error: Throwable, operation: String): SocialLoginResult<Nothing> = when (error) {
        is GetCredentialCancellationException -> SocialLoginResult.Cancelled
        is NoCredentialException -> failure(
            FailureKind.NO_CREDENTIAL,
            operation,
            error.type,
            error,
            hint = "기기에 계정이 없거나, 웹 클라이언트 ID·Android 클라이언트 SHA-1 설정이 틀려도 이렇게 온다",
        )
        is GetCredentialException -> failure(kindOf(error), operation, error.type, error)
        is ClearCredentialException -> failure(kindOf(error), operation, error.type, error)
        is GoogleIdTokenParsingException ->
            failure(FailureKind.UNKNOWN, operation, "GoogleIdTokenParsingException", error)
        is ApiException -> apiException(error, operation)
        is ActivityNotFoundException -> failure(
            FailureKind.UNSUPPORTED,
            operation,
            "ActivityNotFoundException",
            error,
            hint = "Google Play 서비스의 계정 선택 화면이 없다",
        )
        else -> SocialLoginErrors.failureOf(
            throwable = error,
            message = "Google $operation 실패: ${error.javaClass.name}",
            providerId = ID,
        )
    }

    /** [transactionTooLarge] 결과의 vendorCode. */
    const val TRANSACTION_TOO_LARGE: String = "TransactionTooLargeException"

    private const val MAX_CAUSE_DEPTH = 16

    /**
     * 원인 사슬([MAX_CAUSE_DEPTH] 깊이까지)에 `TransactionTooLargeException` 이 있거나, 원인 없이 문구로만 옮겨 온 경우
     * (`GetCredentialException.errorMessage` 나 다른 예외의 `message` 에 클래스 이름)인지.
     */
    fun isTransactionTooLarge(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (current is TransactionTooLargeException) return true
            if (current is GetCredentialException && current.errorMessage?.contains(TRANSACTION_TOO_LARGE) == true) return true
            if (current.message?.contains(TRANSACTION_TOO_LARGE) == true) return true
            current = current.cause
            depth++
        }
        return false
    }

    /** 계정 선택 화면이 Binder 한도에 걸렸을 때의 결과. 원래 예외(앞 시도는 suppressed)를 cause 로 남긴다. */
    fun transactionTooLarge(cause: Throwable): SocialLoginResult.Failure = SocialLoginResult.Failure(
        kind = FailureKind.UNSUPPORTED,
        message = "Google 계정 선택 화면이 Binder 전송 한도를 넘어 뜨지 못했다(기기의 계정이 많을 때 알려진 문제). " +
            "Credential Manager 모드면 기본 모드(LEGACY_GOOGLE_SIGN_IN)로 바꾸고, 그래도 안 되면 다른 로그인 수단을 안내한다",
        providerId = ID,
        vendorCode = TRANSACTION_TOO_LARGE,
        cause = cause,
    )

    fun kindOf(error: GetCredentialException): FailureKind = when (error) {
        // 취소는 toResult 가 먼저 Cancelled 결과로 바꾼다. 여기 오는 것은 분류만 묻는 경우다
        is GetCredentialCancellationException -> FailureKind.UNKNOWN
        is NoCredentialException -> FailureKind.NO_CREDENTIAL
        is GetCredentialInterruptedException -> FailureKind.INTERRUPTED
        is GetCredentialProviderConfigurationException -> FailureKind.CONFIGURATION
        is GetCredentialUnsupportedException -> FailureKind.UNSUPPORTED
        else -> FailureKind.UNKNOWN
    }

    fun kindOf(error: ClearCredentialException): FailureKind = when (error) {
        is ClearCredentialInterruptedException -> FailureKind.INTERRUPTED
        is ClearCredentialProviderConfigurationException -> FailureKind.CONFIGURATION
        is ClearCredentialUnsupportedException -> FailureKind.UNSUPPORTED
        else -> FailureKind.UNKNOWN
    }

    /**
     * Google Sign-In(`ApiException`) 상태 코드 분류. 사용자 취소 12501 은 [SocialLoginResult.Cancelled].
     * 표에 없는 코드는 [FailureKind.UNKNOWN] 이고 vendorCode 에 코드가 남는다.
     */
    fun apiException(error: ApiException, operation: String): SocialLoginResult<Nothing> {
        val code = error.statusCode
        if (code == STATUS_SIGN_IN_CANCELLED) return SocialLoginResult.Cancelled
        val hint = if (code == STATUS_DEVELOPER_ERROR) "웹 클라이언트 ID·Android 클라이언트 SHA-1·패키지명을 확인한다" else null
        return failure(kindOfStatus(code), operation, "ApiException($code)", error, hint)
    }

    fun kindOfStatus(statusCode: Int): FailureKind = when (statusCode) {
        STATUS_SIGN_IN_CANCELLED -> FailureKind.UNKNOWN // apiException 이 먼저 Cancelled 로 바꾼다
        STATUS_SIGN_IN_CURRENTLY_IN_PROGRESS -> FailureKind.BUSY
        STATUS_NETWORK_ERROR, STATUS_TIMEOUT -> FailureKind.NETWORK
        STATUS_DEVELOPER_ERROR -> FailureKind.CONFIGURATION
        STATUS_SIGN_IN_REQUIRED -> FailureKind.NOT_SIGNED_IN
        STATUS_INTERRUPTED, STATUS_CANCELED -> FailureKind.INTERRUPTED
        STATUS_SERVICE_MISSING, STATUS_SERVICE_VERSION_UPDATE_REQUIRED, STATUS_SERVICE_DISABLED,
        STATUS_SERVICE_INVALID, STATUS_SERVICE_UPDATING,
        -> FailureKind.UNSUPPORTED
        else -> FailureKind.UNKNOWN
    }

    // Google Play 서비스 상태 코드(CommonStatusCodes·ConnectionResult·GoogleSignInStatusCodes). 상수를 직접 적어
    // 지원 중단 표시가 붙은 클래스를 참조하지 않는다
    const val STATUS_SERVICE_MISSING: Int = 1
    const val STATUS_SERVICE_VERSION_UPDATE_REQUIRED: Int = 2
    const val STATUS_SERVICE_DISABLED: Int = 3
    const val STATUS_SIGN_IN_REQUIRED: Int = 4
    const val STATUS_NETWORK_ERROR: Int = 7
    const val STATUS_INTERNAL_ERROR: Int = 8
    const val STATUS_SERVICE_INVALID: Int = 9
    const val STATUS_DEVELOPER_ERROR: Int = 10
    const val STATUS_INTERRUPTED: Int = 14
    const val STATUS_TIMEOUT: Int = 15
    const val STATUS_CANCELED: Int = 16
    const val STATUS_SERVICE_UPDATING: Int = 18
    const val STATUS_SIGN_IN_FAILED: Int = 12500
    const val STATUS_SIGN_IN_CANCELLED: Int = 12501
    const val STATUS_SIGN_IN_CURRENTLY_IN_PROGRESS: Int = 12502

    private fun failure(kind: FailureKind, operation: String, vendorCode: String, cause: Throwable, hint: String? = null) =
        SocialLoginResult.Failure(
            kind = kind,
            message = "Google $operation 실패: $vendorCode" + (hint?.let { " — $it" } ?: ""),
            providerId = ID,
            vendorCode = vendorCode,
            cause = cause,
        )
}
