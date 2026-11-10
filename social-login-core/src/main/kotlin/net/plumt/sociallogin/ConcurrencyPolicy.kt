package net.plumt.sociallogin

/**
 * 한 [SocialLoginManager] 에서 호출이 겹칠 때의 정책. 로그인·로그아웃·연결 해제·갱신 모두에 함께 적용된다.
 *
 * 열거형이 아니라 상수 클래스다. 정책은 늘 수 있으므로 `when` 에는 `else` 를 둔다.
 *
 * @property name 정책 이름(예: `REJECT`). 로그·진단용이고 바뀌지 않는다.
 */
public class ConcurrencyPolicy private constructor(public val name: String, internal val kind: Kind) {

    /** 라이브러리 안에서 `else` 없이 모든 정책을 다루려는 닫힌 구분. */
    internal enum class Kind { REJECT, SERIALIZE }

    override fun toString(): String = name

    public companion object {
        /** 진행 중인 호출이 있으면 새 호출을 [FailureKind.BUSY] 로 바로 끝낸다. 기본값. */
        @JvmField
        public val REJECT: ConcurrencyPolicy = ConcurrencyPolicy("REJECT", Kind.REJECT)

        /** 진행 중인 호출이 끝날 때까지 기다렸다가 차례로 실행한다. 기다리는 동안 취소하면 실행하지 않는다. */
        @JvmField
        public val SERIALIZE: ConcurrencyPolicy = ConcurrencyPolicy("SERIALIZE", Kind.SERIALIZE)

        /** 모든 정책. 정의한 순서다. */
        @JvmStatic
        public val entries: List<ConcurrencyPolicy> get() = listOf(REJECT, SERIALIZE)
    }
}
