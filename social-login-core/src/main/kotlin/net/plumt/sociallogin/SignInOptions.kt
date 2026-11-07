package net.plumt.sociallogin

/**
 * 로그인 한 번에 붙이는 선택 사항.
 *
 * @property nonce ID 토큰에 넣을 일회용 값. 앱 서버가 만들어 내려 주고, 받은 ID 토큰의 `nonce` 클레임과
 *   대조해 재전송 공격을 막는다. 지원: Google 의 Credential Manager 모드, 카카오(OIDC 활성화 시 — 꺼져 있으면
 *   [FailureKind.CONFIGURATION]). 네이버와 Google 기본 모드는 nonce 를 담을 수 없어 [FailureKind.UNSUPPORTED] 로
 *   끝난다(조용히 무시하지 않는다).
 * @property extensions 수단별 추가 선택 사항(예: Google 의 호출별 화면 모드). 각 Provider 가 자기가 아는 타입만 읽고
 *   나머지는 무시한다. 같은 클래스는 하나만 넣는다.
 * @throws IllegalArgumentException [nonce] 가 빈 문자열이거나, 공백 문자를 포함하거나,
 *   [MAX_NONCE_LENGTH] 자를 넘을 때. 메시지에는 값 대신 길이만 넣는다. [extensions] 에 같은 클래스가 둘 이상일 때.
 */
public class SignInOptions(
    public val nonce: String?,
    extensions: Collection<Extension>,
) {

    /** 수단별 추가 선택 사항 없이 [nonce] 만 둔다. 0.0.1 의 생성자 시그니처(`()`·`(String)`)를 그대로 지킨다. */
    @JvmOverloads
    public constructor(nonce: String? = null) : this(nonce, emptyList())

    public val extensions: List<Extension> = extensions.toList()

    init {
        if (nonce != null) {
            require(nonce.length in 1..MAX_NONCE_LENGTH) {
                "nonce 길이는 1..$MAX_NONCE_LENGTH 이어야 한다: 받은 길이 ${nonce.length}"
            }
            require(nonce.none { it.isWhitespace() }) {
                "nonce 에 공백 문자가 있으면 안 된다(길이 ${nonce.length})"
            }
        }
        val types = this.extensions.map { it.javaClass }
        require(types.distinct().size == types.size) {
            "extensions 에 같은 클래스가 둘 이상이다: 받은 값 ${types.map { it.simpleName }}"
        }
    }

    /** [type] 의 추가 선택 사항. 없으면 null. */
    public fun <T : Extension> extension(type: Class<T>): T? =
        extensions.firstOrNull { type.isInstance(it) }?.let(type::cast)

    /** [T] 의 추가 선택 사항. 없으면 null. */
    public inline fun <reified T : Extension> extension(): T? = extension(T::class.java)

    override fun equals(other: Any?): Boolean =
        other is SignInOptions && other.nonce == nonce && other.extensions == extensions

    override fun hashCode(): Int = 31 * nonce.hashCode() + extensions.hashCode()

    /** nonce 는 가리고 추가 선택 사항은 클래스 이름만 보인다. */
    override fun toString(): String =
        if (extensions.isEmpty()) {
            "SignInOptions(nonce=${redact(nonce)})"
        } else {
            "SignInOptions(nonce=${redact(nonce)}, extensions=${extensions.map { it.javaClass.simpleName }})"
        }

    /**
     * 수단별 추가 선택 사항의 표지. 수단 모듈이 구현 클래스를 공개하고, 그 Provider 가 [extension] 으로 꺼내 읽는다.
     * 구현은 값 객체(equals·hashCode)로 두고, `toString` 에 비밀값을 넣지 않는다.
     */
    public interface Extension

    public companion object {
        /** [nonce] 의 최대 길이. */
        public const val MAX_NONCE_LENGTH: Int = 512

        /** 아무 선택 사항도 없는 기본값. */
        @JvmField
        public val DEFAULT: SignInOptions = SignInOptions()
    }
}
