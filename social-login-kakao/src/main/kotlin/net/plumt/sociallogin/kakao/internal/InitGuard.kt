package net.plumt.sociallogin.kakao.internal

/**
 * 프로세스 전역 SDK 를 한 번만 초기화하는 가드.
 *
 * - 처음 부를 때 [init] 을 실행한다. [init] 이 던지면 표시를 세우지 않아 다음 호출이 다시 시도한다.
 * - 같은 키로 다시 부르면 아무 일도 하지 않는다.
 * - 다른 키로 부르면 [IllegalStateException] — SDK 가 전역 상태라 키를 바꿔 끼울 수 없다.
 */
internal class InitGuard(private val sdkName: String) {
    private var initializedKey: String? = null

    @Synchronized
    fun ensure(key: String, init: () -> Unit) {
        val current = initializedKey
        if (current == null) {
            init()
            initializedKey = key
        } else {
            check(current == key) {
                "$sdkName 가 다른 키로 이미 초기화됐다. SDK 가 프로세스 전역이라 한 프로세스에 키는 하나다"
            }
        }
    }
}
