package net.plumt.sociallogin.kakao.internal

import com.kakao.sdk.auth.model.OAuthToken
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 카카오 SDK 의 (token, error) 콜백을 결과 하나로 접는다. 둘 다 비면 SDK 계약 위반이라 [IllegalStateException]. */
internal fun tokenCallbackResult(token: OAuthToken?, error: Throwable?): Result<OAuthToken> = when {
    error != null -> Result.failure(error)
    token != null -> Result.success(token)
    else -> Result.failure(IllegalStateException("카카오 SDK 가 토큰과 오류를 모두 비운 채 콜백했다"))
}

/** 카카오 SDK 의 (error) 콜백을 결과 하나로 접는다. */
internal fun unitCallbackResult(error: Throwable?): Result<Unit> =
    if (error != null) Result.failure(error) else Result.success(Unit)

/**
 * 아직 기다리는 중일 때만 재개한다. 호출 코루틴이 취소된 뒤 늦게 온 콜백이나, SDK 가 두 번 부른 콜백은 버린다.
 * @return 재개했으면 true.
 */
internal fun <T> CancellableContinuation<T>.resumeIfActive(result: Result<T>): Boolean {
    if (!isActive) return false
    result.fold(onSuccess = { resume(it) }, onFailure = { resumeWithException(it) })
    return true
}
