package net.plumt.sociallogin

/**
 * Provider 가 로그인 말고 더 지원하는 동작.
 *
 * 로그인은 모든 Provider 가 지원하므로 여기 없다. [SocialLoginManager] 는 Provider 가 선언하지 않은
 * 동작을 Provider 를 부르지 않고 [FailureKind.UNSUPPORTED] 로 끝낸다.
 *
 * 열거형이 아니라 상수 클래스다. 동작은 늘 수 있으므로 `when` 에는 `else` 를 둔다. 같은 동작은 언제나 같은 인스턴스다.
 *
 * @property name 동작 이름(예: `SIGN_OUT`). 로그·진단용이고 바뀌지 않는다.
 */
public class Capability private constructor(public val name: String) {

    override fun toString(): String = name

    public companion object {
        /** 이 기기의 로그인 상태를 지운다. 계정 연결은 남는다. */
        @JvmField
        public val SIGN_OUT: Capability = Capability("SIGN_OUT")

        /** 앱과 소셜 계정의 연결을 끊는다(회원 탈퇴 시). */
        @JvmField
        public val UNLINK: Capability = Capability("UNLINK")

        /** 저장된 갱신 토큰으로 액세스 토큰을 새로 받는다. */
        @JvmField
        public val REFRESH_TOKEN: Capability = Capability("REFRESH_TOKEN")

        /** 모든 동작. 정의한 순서다. 동작이 늘면 이 목록도 는다. */
        @JvmStatic
        public val entries: List<Capability> get() = listOf(SIGN_OUT, UNLINK, REFRESH_TOKEN)
    }
}
