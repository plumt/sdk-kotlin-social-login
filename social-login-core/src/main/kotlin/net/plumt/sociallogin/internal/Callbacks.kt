package net.plumt.sociallogin.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.plumt.sociallogin.SocialLoginCallHandle
import net.plumt.sociallogin.SocialLoginCallback
import net.plumt.sociallogin.SocialLoginErrors
import net.plumt.sociallogin.SocialLoginResult
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * [block] 을 호출마다 새 코루틴에서 돌리고 결과를 [executor] 에서 [callback] 으로 한 번 준다. 콜백 형태 오버로드는 모두 이 함수
 * 하나로 `suspend` 형태를 감싼다 — 로직을 따로 두지 않는다.
 *
 * - 화면 수명과 무관한 코루틴이다. 화면 회전으로 취소되지 않는다. 멈추려면 핸들을 [SocialLoginCallHandle.cancel] 한다.
 * - [block] 이 예외를 던지면(직접 구현한 Manager 등) [SocialLoginErrors.failureOf] 로 감싼 실패를 준다.
 * - [executor] 가 작업을 거절하면 결과를 버리고 핸들을 끝낸다.
 */
internal fun <T> launchCallback(
    executor: Executor,
    callback: SocialLoginCallback<T>,
    block: suspend () -> SocialLoginResult<T>,
): SocialLoginCallHandle {
    val job = Job()
    val handle = CallbackHandle(job)
    CoroutineScope(Dispatchers.Default + job).launch {
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SocialLoginErrors.failureOf(e, message = "콜백 호출이 예외를 던졌다: ${e.javaClass.name}")
        }
        handle.deliver(executor, callback, result)
    }
    return handle
}

/** 이미 정해진 [result] 를 곧바로 [executor] 로 넘긴다(코루틴 없이). 가짜 구현이 쓴다. */
internal fun <T> deliverNow(
    executor: Executor,
    callback: SocialLoginCallback<T>,
    result: SocialLoginResult<T>,
): SocialLoginCallHandle {
    val handle = CallbackHandle(null)
    handle.deliver(executor, callback, result)
    return handle
}

internal class CallbackHandle(private val job: Job?) : SocialLoginCallHandle {

    private val state = AtomicInteger(ACTIVE)

    override val isActive: Boolean get() = state.get() == ACTIVE

    override fun cancel() {
        state.compareAndSet(ACTIVE, CANCELLED)
        job?.cancel()
    }

    /** 취소되지 않았으면 [executor] 에서 [callback] 을 한 번 부른다. 실행기에서 차례를 기다리는 사이 취소돼도 부르지 않는다. */
    fun <T> deliver(executor: Executor, callback: SocialLoginCallback<T>, result: SocialLoginResult<T>) {
        if (state.get() != ACTIVE) return
        try {
            executor.execute {
                if (state.compareAndSet(ACTIVE, DONE)) callback.onResult(result)
            }
        } catch (e: RejectedExecutionException) {
            state.compareAndSet(ACTIVE, DONE)
        }
    }

    override fun toString(): String = "SocialLoginCallHandle(active=$isActive)"

    private companion object {
        const val ACTIVE = 0
        const val DONE = 1
        const val CANCELLED = 2
    }
}
