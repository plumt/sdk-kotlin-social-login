package net.plumt.sociallogin

/**
 * 소셜 수단이 돌려준 토큰.
 *
 * **클라이언트가 받은 토큰은 그 자체로 인증이 아니다.** 앱 서버가 [idToken] 의 서명·`aud`·`exp`·`nonce` 를,
 * 또는 [accessToken] 을 소셜 수단의 토큰 정보 API 로 검증한 뒤에야 사용자를 식별한다.
 *
 * [toString] 은 토큰 값을 가린다. 로그·크래시 리포트에 이 객체를 그대로 찍어도 토큰이 새지 않는다.
 * 값을 직접 꺼내 찍는 것은 호출부의 책임이다.
 *
 * @property accessToken OAuth 액세스 토큰. Google 처럼 ID 토큰만 주는 수단은 null.
 * @property idToken OpenID Connect ID 토큰(JWT). 주지 않는 수단(네이버, OIDC 를 켜지 않은 카카오)은 null.
 * @property refreshToken 갱신 토큰. 수단이 주지 않으면 null.
 * @property accessTokenExpiresAtMillis 액세스 토큰 만료 시각(Unix epoch 밀리초). 모르면 null.
 * @property refreshTokenExpiresAtMillis 갱신 토큰 만료 시각(Unix epoch 밀리초). 모르면 null.
 * @property scopes 동의된 범위. 수단이 알려 주지 않으면 빈 집합.
 * @throws IllegalArgumentException [accessToken]·[idToken] 이 둘 다 null 이거나, null 이 아닌 토큰이 공백뿐이거나,
 *   만료 시각이 음수이거나, [scopes] 에 공백뿐인 원소가 있을 때. 메시지에는 토큰 값 대신 길이만 넣는다.
 */
public class SocialToken(
    public val accessToken: String?,
    public val idToken: String?,
    public val refreshToken: String? = null,
    public val accessTokenExpiresAtMillis: Long? = null,
    public val refreshTokenExpiresAtMillis: Long? = null,
    public val scopes: Set<String> = emptySet(),
) {

    init {
        require(accessToken != null || idToken != null) {
            "accessToken·idToken 중 하나는 있어야 한다: 둘 다 null"
        }
        requireNotBlankSecret("accessToken", accessToken)
        requireNotBlankSecret("idToken", idToken)
        requireNotBlankSecret("refreshToken", refreshToken)
        require(accessTokenExpiresAtMillis == null || accessTokenExpiresAtMillis >= 0) {
            "accessTokenExpiresAtMillis 는 0 이상이어야 한다: 받은 값 $accessTokenExpiresAtMillis"
        }
        require(refreshTokenExpiresAtMillis == null || refreshTokenExpiresAtMillis >= 0) {
            "refreshTokenExpiresAtMillis 는 0 이상이어야 한다: 받은 값 $refreshTokenExpiresAtMillis"
        }
        require(scopes.none { it.isBlank() }) {
            "scopes 에 공백뿐인 원소가 있다: 받은 값 $scopes"
        }
    }

    /**
     * [nowMillis] 기준으로 액세스 토큰이 만료됐는지. 만료 시각을 모르면 false.
     * 시각은 호출부가 넣는다(테스트에서 시계를 고정할 수 있게).
     */
    public fun isAccessTokenExpired(nowMillis: Long): Boolean =
        accessTokenExpiresAtMillis != null && nowMillis >= accessTokenExpiresAtMillis

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SocialToken) return false
        return accessToken == other.accessToken &&
            idToken == other.idToken &&
            refreshToken == other.refreshToken &&
            accessTokenExpiresAtMillis == other.accessTokenExpiresAtMillis &&
            refreshTokenExpiresAtMillis == other.refreshTokenExpiresAtMillis &&
            scopes == other.scopes
    }

    override fun hashCode(): Int {
        var result = accessToken.hashCode()
        result = 31 * result + idToken.hashCode()
        result = 31 * result + refreshToken.hashCode()
        result = 31 * result + accessTokenExpiresAtMillis.hashCode()
        result = 31 * result + refreshTokenExpiresAtMillis.hashCode()
        result = 31 * result + scopes.hashCode()
        return result
    }

    override fun toString(): String =
        "SocialToken(accessToken=${redact(accessToken)}, idToken=${redact(idToken)}, " +
            "refreshToken=${redact(refreshToken)}, accessTokenExpiresAtMillis=$accessTokenExpiresAtMillis, " +
            "refreshTokenExpiresAtMillis=$refreshTokenExpiresAtMillis, scopes=$scopes)"

    private companion object {
        fun requireNotBlankSecret(name: String, value: String?) {
            require(value == null || value.isNotBlank()) {
                "$name 이 공백뿐이다(길이 ${value?.length}). 없으면 null 을 넣는다"
            }
        }
    }
}

/** 값이 있으면 가림 표시, 없으면 null 을 그대로 쓴다. */
internal fun redact(value: String?): String = if (value == null) "null" else REDACTED

internal const val REDACTED: String = "<redacted>"
