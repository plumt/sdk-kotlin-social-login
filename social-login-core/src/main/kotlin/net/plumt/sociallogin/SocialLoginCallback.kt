package net.plumt.sociallogin

/**
 * 콜백 형태 호출의 결과 받는 곳. [SocialLoginManager] 의 콜백 오버로드가 정한 실행기(`Executor`)에서 한 번 불린다.
 * 취소([SocialLoginCallHandle.cancel])했으면 불리지 않는다.
 */
public fun interface SocialLoginCallback<in T> {
    /** 결과를 받는다. `suspend` 형태와 같은 [SocialLoginResult] 다. */
    public fun onResult(result: SocialLoginResult<T>)
}

/**
 * 콜백 형태 호출의 취소 핸들. [cancel] 하면 진행 중인 호출 코루틴을 취소하고 콜백을 부르지 않는다.
 * 이미 결과를 받았거나 이미 취소했으면 아무것도 하지 않는다. 이미 뜬 벤더 SDK 화면은 닫히지 않을 수 있다.
 */
public interface SocialLoginCallHandle {
    /** 호출을 멈추고 콜백을 부르지 않게 한다. 여러 번 불러도 된다. 어느 스레드에서 불러도 된다. */
    public fun cancel()

    /** 결과를 아직 기다리는 중인가. 결과를 받았거나 취소했으면 false. */
    public val isActive: Boolean
}
