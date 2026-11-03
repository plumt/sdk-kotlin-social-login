package net.plumt.sociallogin

import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 수단과 무관한 예외 분류. Provider 구현이 벤더 고유 오류를 먼저 분류하고, 남은 예외를 여기로 넘긴다.
 *
 * 새 수단을 붙이는 쪽도 같은 규칙을 쓰도록 공개한다.
 */
public object SocialLoginErrors {

    /** 원인 사슬을 따라가는 최대 깊이. 순환 사슬에서 멈추기 위한 상한이다. */
    public const val MAX_CAUSE_DEPTH: Int = 16

    /**
     * 예외를 갈래로 나눈다.
     *
     * - [IOException]·[TimeoutException] 이 원인 사슬([MAX_CAUSE_DEPTH] 깊이까지)에 있으면 [FailureKind.NETWORK]
     * - 그 밖은 [FailureKind.UNKNOWN]
     *
     * @throws IllegalArgumentException [throwable] 이 [CancellationException] 일 때. 코루틴 취소는 분류하지 않고
     *   다시 던져야 한다.
     */
    public fun classify(throwable: Throwable): FailureKind {
        require(throwable !is CancellationException) {
            "CancellationException 은 분류하지 않고 다시 던진다: 받은 값 ${throwable.javaClass.name}"
        }
        return if (hasNetworkCause(throwable)) FailureKind.NETWORK else FailureKind.UNKNOWN
    }

    /**
     * [throwable] 을 [classify] 한 [SocialLoginResult.Failure] 로 감싼다. [throwable] 은 `cause` 로 보존한다.
     *
     * @param message 개발자용 설명. 토큰·이메일·키 값을 넣지 않는다.
     * @throws IllegalArgumentException [throwable] 이 [CancellationException] 이거나 [message] 가 공백뿐일 때.
     */
    public fun failureOf(
        throwable: Throwable,
        message: String,
        providerId: ProviderId? = null,
        vendorCode: String? = null,
    ): SocialLoginResult.Failure = SocialLoginResult.Failure(
        kind = classify(throwable),
        message = message,
        providerId = providerId,
        vendorCode = vendorCode,
        cause = throwable,
    )

    private fun hasNetworkCause(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (current is IOException || current is TimeoutException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
