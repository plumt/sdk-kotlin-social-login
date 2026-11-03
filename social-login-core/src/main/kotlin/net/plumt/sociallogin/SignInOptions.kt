package net.plumt.sociallogin

/**
 * 로그인 한 번에 붙이는 선택 사항.
 *
 * @property nonce ID 토큰에 넣을 일회용 값. 앱 서버가 만들어 내려 주고, 받은 ID 토큰의 `nonce` 클레임과
 *   대조해 재전송 공격을 막는다. 지원: Google, 카카오(OIDC 활성화 시). 네이버는 ID 토큰이 없어
 *   nonce 를 넘기면 [FailureKind.UNSUPPORTED] 로 끝난다(조용히 무시하지 않는다).
 * @throws IllegalArgumentException [nonce] 가 빈 문자열이거나, 공백 문자를 포함하거나,
 *   [MAX_NONCE_LENGTH] 자를 넘을 때. 메시지에는 값 대신 길이만 넣는다.
 */
public class SignInOptions(
    public val nonce: String? = null,
) {

    init {
        if (nonce != null) {
            require(nonce.length in 1..MAX_NONCE_LENGTH) {
                "nonce 길이는 1..$MAX_NONCE_LENGTH 이어야 한다: 받은 길이 ${nonce.length}"
            }
            require(nonce.none { it.isWhitespace() }) {
                "nonce 에 공백 문자가 있으면 안 된다(길이 ${nonce.length})"
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is SignInOptions && other.nonce == nonce

    override fun hashCode(): Int = nonce.hashCode()

    override fun toString(): String = "SignInOptions(nonce=${redact(nonce)})"

    public companion object {
        /** [nonce] 의 최대 길이. */
        public const val MAX_NONCE_LENGTH: Int = 512

        /** 아무 선택 사항도 없는 기본값. */
        @JvmField
        public val DEFAULT: SignInOptions = SignInOptions()
    }
}
