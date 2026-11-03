package net.plumt.sociallogin.naver

/**
 * 네이버 로그인 설정. 값은 네이버 개발자 센터 > 애플리케이션 > 애플리케이션 정보 에 있다.
 * 앱이 런타임에 넣는다(예: `BuildConfig` 나 원격 설정). 이 라이브러리는 값을 갖고 있지 않다.
 *
 * Client Secret 은 앱에 들어가는 순간 디컴파일로 읽힐 수 있다. 네이버 로그인 SDK 의 구조상 피할 수 없으니,
 * 앱 서버는 클라이언트가 보낸 토큰을 반드시 네이버 API 로 검증하고, Secret 이 새면 개발자 센터에서 재발급한다.
 *
 * @property clientId Client ID. 1..[MAX_CREDENTIAL_LENGTH] 자, 공백 문자 없음.
 * @property clientSecret Client Secret. 1..[MAX_CREDENTIAL_LENGTH] 자, 공백 문자 없음.
 * @property clientName 네이버 로그인 화면에 보일 앱 이름. 1..[MAX_NAME_LENGTH] 자, 공백뿐이면 안 된다.
 * @throws IllegalArgumentException 위 조건을 어길 때. 메시지에는 값 대신 길이만 넣는다.
 */
public class NaverLoginConfig(
    public val clientId: String,
    public val clientSecret: String,
    public val clientName: String,
) {

    init {
        requireCredential("clientId", clientId)
        requireCredential("clientSecret", clientSecret)
        require(clientName.length in 1..MAX_NAME_LENGTH && clientName.isNotBlank()) {
            "clientName 은 공백뿐이 아닌 1..$MAX_NAME_LENGTH 자여야 한다: 받은 길이 ${clientName.length}"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NaverLoginConfig && other.clientId == clientId && other.clientSecret == clientSecret &&
            other.clientName == clientName

    override fun hashCode(): Int = (clientId.hashCode() * 31 + clientSecret.hashCode()) * 31 + clientName.hashCode()

    /** ID 와 Secret 은 가린다. */
    override fun toString(): String =
        "NaverLoginConfig(clientId=<redacted>, clientSecret=<redacted>, clientName=$clientName)"

    public companion object {
        /** [clientId]·[clientSecret] 의 최대 길이. */
        public const val MAX_CREDENTIAL_LENGTH: Int = 128

        /** [clientName] 의 최대 길이. */
        public const val MAX_NAME_LENGTH: Int = 100

        private fun requireCredential(name: String, value: String) {
            require(value.length in 1..MAX_CREDENTIAL_LENGTH && value.none { it.isWhitespace() }) {
                "$name 은 공백 문자 없는 1..$MAX_CREDENTIAL_LENGTH 자여야 한다: 받은 길이 ${value.length}"
            }
        }
    }
}
