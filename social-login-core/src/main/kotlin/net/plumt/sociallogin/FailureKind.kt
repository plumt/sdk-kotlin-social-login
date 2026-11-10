package net.plumt.sociallogin

/**
 * 실패의 갈래. 화면은 이 값으로 안내 문구와 재시도 여부를 고른다.
 *
 * 수단마다 다른 벤더 오류 코드를 이 갈래로 접는다. 원래 코드는 [SocialLoginResult.Failure.vendorCode] 에,
 * 원래 예외는 [SocialLoginResult.Failure.cause] 에 남는다.
 *
 * 열거형이 아니라 상수 클래스다. 갈래는 늘 수 있으므로 `when` 에는 `else` 를 둔다 — 갈래가 늘어도 앱 코드가
 * 다시 컴파일할 때 깨지지 않는다. 같은 갈래는 언제나 같은 인스턴스라 `==`·`when` 으로 비교한다.
 *
 * @property name 갈래 이름(예: `NETWORK`). 로그·진단용이고 바뀌지 않는다.
 * @property retryable 같은 요청을 잠시 뒤 다시 보내면 성공할 수 있는 갈래인지.
 */
public class FailureKind internal constructor(
    public val name: String,
    public val retryable: Boolean,
) {

    override fun toString(): String = name

    public companion object {
        private val all = ArrayList<FailureKind>()

        private fun of(name: String, retryable: Boolean): FailureKind = FailureKind(name, retryable).also { all += it }

        /** 망 연결 실패·시간 초과. */
        @JvmField
        public val NETWORK: FailureKind = of("NETWORK", true)

        /** 소셜 수단 서버가 오류를 냈거나 점검 중이다. */
        @JvmField
        public val PROVIDER_SERVER: FailureKind = of("PROVIDER_SERVER", true)

        /** 다른 화면·시스템 이벤트에 끊겼다. 사용자가 다시 시도하면 된다. */
        @JvmField
        public val INTERRUPTED: FailureKind = of("INTERRUPTED", true)

        /** 같은 [SocialLoginManager] 에서 다른 호출이 진행 중이라 거절했다([ConcurrencyPolicy.REJECT]). */
        @JvmField
        public val BUSY: FailureKind = of("BUSY", true)

        /** 앱 키·패키지명·서명 키 해시·리다이렉트 스킴 등 콘솔·앱 설정이 맞지 않는다. 개발자가 고칠 문제다. */
        @JvmField
        public val CONFIGURATION: FailureKind = of("CONFIGURATION", false)

        /** 토큰이 만료·폐기됐거나 인가 코드가 틀렸다. 다시 로그인해야 한다. */
        @JvmField
        public val AUTHENTICATION: FailureKind = of("AUTHENTICATION", false)

        /** 사용자가 동의를 거부했거나 연령·계정 제한으로 접근이 막혔다. */
        @JvmField
        public val ACCESS_DENIED: FailureKind = of("ACCESS_DENIED", false)

        /** 로그인한 적이 없어 로그아웃·연결 해제·갱신할 토큰이 없다. */
        @JvmField
        public val NOT_SIGNED_IN: FailureKind = of("NOT_SIGNED_IN", false)

        /** 기기에 쓸 수 있는 계정(자격 증명)이 없다. Google 에서 기기에 계정이 없을 때 등. */
        @JvmField
        public val NO_CREDENTIAL: FailureKind = of("NO_CREDENTIAL", false)

        /** 요청한 수단이 [SocialLoginManager] 에 등록돼 있지 않다. */
        @JvmField
        public val PROVIDER_NOT_REGISTERED: FailureKind = of("PROVIDER_NOT_REGISTERED", false)

        /** 수단이 이 동작·옵션을 지원하지 않거나 기기·앱 환경이 지원하지 않는다. */
        @JvmField
        public val UNSUPPORTED: FailureKind = of("UNSUPPORTED", false)

        /** 위 어디에도 들지 않는다. [SocialLoginResult.Failure.cause] 를 본다. */
        @JvmField
        public val UNKNOWN: FailureKind = of("UNKNOWN", false)

        /**
         * [SocialLoginConfig.Builder.callTimeoutMillis] 로 정한 시간 안에 호출이 끝나지 않아 중단했다.
         * 벤더 SDK 화면은 남아 있을 수 있다. 망 시간 초과는 [NETWORK] 다.
         */
        @JvmField
        public val TIMEOUT: FailureKind = of("TIMEOUT", true)

        /** 모든 갈래. 정의한 순서다. 갈래가 늘면 이 목록도 는다. */
        @JvmStatic
        public val entries: List<FailureKind> get() = all.toList()
    }
}
