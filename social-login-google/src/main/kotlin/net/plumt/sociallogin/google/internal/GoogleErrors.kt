package net.plumt.sociallogin.google.internal

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
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginErrors
import net.plumt.sociallogin.SocialLoginResult

/**
 * Credential Manager·googleid 오류를 결과로 바꾼다. 예외 계층이 sealed 가 아니라 모르는 하위 타입은 UNKNOWN 이다.
 * vendorCode 는 예외의 `type` 문자열(예: `android.credentials.GetCredentialException.TYPE_USER_CANCELED`)이다.
 */
internal object GoogleErrors {

    private val ID = ProviderId.GOOGLE

    fun toResult(error: Throwable, operation: String): SocialLoginResult<Nothing> = when (error) {
        is GetCredentialCancellationException -> SocialLoginResult.Cancelled
        is GetCredentialException -> failure(kindOf(error), operation, error.type, error)
        is ClearCredentialException -> failure(kindOf(error), operation, error.type, error)
        is GoogleIdTokenParsingException ->
            failure(FailureKind.UNKNOWN, operation, "GoogleIdTokenParsingException", error)
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
     * 원인 사슬([MAX_CAUSE_DEPTH] 깊이까지)에 `TransactionTooLargeException` 이 있거나, Credential Manager 가 원인 없이
     * 문구로만 옮긴 경우(`GetCredentialException.errorMessage` 에 클래스 이름)인지.
     */
    fun isTransactionTooLarge(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (current is TransactionTooLargeException) return true
            if (current is GetCredentialException && current.errorMessage?.contains(TRANSACTION_TOO_LARGE) == true) return true
            current = current.cause
            depth++
        }
        return false
    }

    /** 시트·버튼 흐름 모두 Binder 한도에 걸렸을 때의 결과. 원래 예외(앞 시도는 suppressed)를 cause 로 남긴다. */
    fun transactionTooLarge(cause: Throwable): SocialLoginResult.Failure = SocialLoginResult.Failure(
        kind = FailureKind.UNSUPPORTED,
        message = "Google 계정 선택 화면이 Binder 전송 한도를 넘었다(기기의 계정이 많을 때 알려진 문제). " +
            "버튼 흐름 재시도도 실패했다 — 다른 로그인 수단을 안내한다",
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

    private fun failure(kind: FailureKind, operation: String, vendorCode: String, cause: Throwable) =
        SocialLoginResult.Failure(
            kind = kind,
            message = "Google $operation 실패: $vendorCode",
            providerId = ID,
            vendorCode = vendorCode,
            cause = cause,
        )
}
