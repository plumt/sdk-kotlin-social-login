package net.plumt.sociallogin

/**
 * 소셜 로그인 수단의 식별자.
 *
 * 열거형이 아니라 값 객체라서, 새 수단(Apple·Facebook 등)을 붙일 때 이 모듈을 고치지 않고
 * `ProviderId("apple")` 처럼 만들어 쓴다. 잘 알려진 수단은 [KAKAO]·[NAVER]·[GOOGLE] 상수로 둔다.
 *
 * @property value 영소문자로 시작하고 영소문자·숫자·`_`·`-` 로 이뤄진 1..[MAX_LENGTH] 자 문자열.
 * @throws IllegalArgumentException [value] 가 형식에 맞지 않을 때.
 */
public class ProviderId(public val value: String) {

    init {
        require(value.length in 1..MAX_LENGTH && PATTERN.matches(value)) {
            "ProviderId 는 영소문자로 시작하는 1..$MAX_LENGTH 자 [a-z0-9_-] 문자열이어야 한다: " +
                "받은 값 '${value.take(MAX_LENGTH)}${if (value.length > MAX_LENGTH) "…" else ""}'(길이 ${value.length})"
        }
    }

    override fun equals(other: Any?): Boolean = other is ProviderId && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ProviderId($value)"

    public companion object {
        /** [value] 의 최대 길이. */
        public const val MAX_LENGTH: Int = 32

        private val PATTERN = Regex("[a-z][a-z0-9_-]*")

        /** 카카오. */
        @JvmField
        public val KAKAO: ProviderId = ProviderId("kakao")

        /** 네이버. */
        @JvmField
        public val NAVER: ProviderId = ProviderId("naver")

        /** Google. */
        @JvmField
        public val GOOGLE: ProviderId = ProviderId("google")
    }
}
