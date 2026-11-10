package net.plumt.sociallogin

/**
 * 로그인 결과로 받은 계정 정보.
 *
 * **Provider 가 로그인 응답으로 준 것만 담는다.** 프로필을 얻으려고 추가 API 를 부르지 않는다.
 * 그래서 카카오·네이버는 토큰만 오고 [email]·[displayName] 은 null 이다. 프로필이 필요하면 앱 서버가
 * 토큰을 검증하면서 소셜 수단의 사용자 정보 API 로 받는다.
 *
 * 이메일을 사용자 식별 키로 쓰지 않는다. 수단마다 주지 않을 수 있고 사용자가 바꿀 수 있다.
 * 식별은 서버가 검증한 토큰의 고유 ID(`sub` 등)로 한다.
 *
 * [toString] 은 [email]·[displayName]·[profileImageUrl] 과 토큰을 가린다.
 *
 * @property providerId 로그인한 수단.
 * @property token 받은 토큰. 서버 검증용이다.
 * @property email 수단이 로그인 응답에 넣어 준 이메일. 없으면 null.
 * @property displayName 수단이 로그인 응답에 넣어 준 표시 이름. 없으면 null.
 * @property profileImageUrl 수단이 로그인 응답에 넣어 준 프로필 이미지 URL. 없으면 null.
 * @throws IllegalArgumentException null 이 아닌 문자열 필드가 공백뿐일 때(없으면 null 을 넣는다).
 */
public class SocialAccount(
    public val providerId: ProviderId,
    public val token: SocialToken,
    public val email: String?,
    public val displayName: String?,
    public val profileImageUrl: String?,
) {

    /** 이메일·이름·이미지 없이 만든다(카카오·네이버처럼 토큰만 오는 수단). */
    public constructor(providerId: ProviderId, token: SocialToken) : this(providerId, token, null, null, null)

    init {
        require(email == null || email.isNotBlank()) { "email 이 공백뿐이다(길이 ${email?.length}). 없으면 null 을 넣는다" }
        require(displayName == null || displayName.isNotBlank()) {
            "displayName 이 공백뿐이다(길이 ${displayName?.length}). 없으면 null 을 넣는다"
        }
        require(profileImageUrl == null || profileImageUrl.isNotBlank()) {
            "profileImageUrl 이 공백뿐이다(길이 ${profileImageUrl?.length}). 없으면 null 을 넣는다"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SocialAccount) return false
        return providerId == other.providerId &&
            token == other.token &&
            email == other.email &&
            displayName == other.displayName &&
            profileImageUrl == other.profileImageUrl
    }

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + email.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + profileImageUrl.hashCode()
        return result
    }

    override fun toString(): String =
        "SocialAccount(providerId=${providerId.value}, token=$token, email=${redact(email)}, " +
            "displayName=${redact(displayName)}, profileImageUrl=${redact(profileImageUrl)})"
}
