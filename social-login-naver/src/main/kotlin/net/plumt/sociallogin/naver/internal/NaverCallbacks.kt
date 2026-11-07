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
 * - SDK 는 HTTP 4xx 로 `onFailure` 를 부를 때 마지막 오류 코드를 갱신하지 않는다. 그 코드는 기기에 영속돼 지난 실패의
 *   코드(예: 연결 오류·사용자 취소)가 남아 있을 수 있다. 그래서 콜백을 만들 때의 코드를 기억해 두고, `onFailure` 때
 *   코드가 그대로면 [NidOAuthErrorCode.NONE] 으로 보고 HTTP 상태로 분류하게 한다.
 * - 이미 재개됐거나 호출 코루틴이 취소됐으면 콜백을 버린다.
 */
internal fun naverCallback(
    cont: CancellableContinuation<Unit>,
    lastErrorCode: () -> NidOAuthErrorCode,
): OAuthLoginCallback = object : OAuthLoginCallback {
    private val codeBefore: NidOAuthErrorCode = lastErrorCode()

    override fun onSuccess() {
        if (cont.isActive) cont.resume(Unit)
    }

    override fun onFailure(httpStatus: Int, message: String) {
        if (!cont.isActive) return
        val now = lastErrorCode()
        val code = if (now == codeBefore) NidOAuthErrorCode.NONE else now
        cont.resumeWithException(NaverSdkException(code, httpStatus, null))
    }

    override fun onError(errorCode: Int, message: String) {
        if (cont.isActive) cont.resumeWithException(NaverSdkException(lastErrorCode(), null, errorCode))
    }
}
