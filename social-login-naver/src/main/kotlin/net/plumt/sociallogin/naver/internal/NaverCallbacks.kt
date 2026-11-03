package net.plumt.sociallogin.naver.internal

import com.navercorp.nid.oauth.NidOAuthErrorCode
import com.navercorp.nid.oauth.OAuthLoginCallback
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 네이버 SDK 의 3갈래 콜백을 코루틴 재개 하나로 접는다.
 *
 * - `onSuccess` → 재개
 * - `onFailure(httpStatus, message)`·`onError(errorCode, message)` → [NaverSdkException] 으로 재개.
 *   [lastErrorCode] 로 SDK 의 마지막 오류 코드를 읽는다. 콜백 문구는 버린다.
 * - 이미 재개됐거나 호출 코루틴이 취소됐으면 콜백을 버린다.
 */
internal fun naverCallback(
    cont: CancellableContinuation<Unit>,
    lastErrorCode: () -> NidOAuthErrorCode,
): OAuthLoginCallback = object : OAuthLoginCallback {
    override fun onSuccess() {
        if (cont.isActive) cont.resume(Unit)
    }

    override fun onFailure(httpStatus: Int, message: String) {
        if (cont.isActive) cont.resumeWithException(NaverSdkException(lastErrorCode(), httpStatus, null))
    }

    override fun onError(errorCode: Int, message: String) {
        if (cont.isActive) cont.resumeWithException(NaverSdkException(lastErrorCode(), null, errorCode))
    }
}
