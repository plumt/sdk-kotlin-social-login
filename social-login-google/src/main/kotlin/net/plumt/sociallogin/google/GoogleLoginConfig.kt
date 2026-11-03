package net.plumt.sociallogin.google

/** Google 로그인 화면의 형태. */
public enum class GoogleSignInMode {
    /** 화면 아래에서 올라오는 계정 선택 시트(`GetGoogleIdOption`). 앱 시작 때 자동 로그인 시도에도 쓴다. */
    BOTTOM_SHEET,

    /** "Google 계정으로 로그인" 버튼을 눌렀을 때의 전체 흐름(`GetSignInWithGoogleOption`). 계정 추가도 된다. */
    BUTTON,
}

/**
 * Google 로그인 설정.
 *
 * @property serverClientId Google Cloud 콘솔(또는 Firebase 콘솔)의 **웹 애플리케이션** OAuth 클라이언트 ID.
 *   Android 클라이언트 ID 를 넣으면 ID 토큰이 나오지 않는다. 앱이 런타임에 넣는다.
 *   ID 토큰의 `aud` 가 이 값이 되므로 서버는 같은 값으로 검증한다.
 * @property mode 로그인 화면 형태.
 * @property filterByAuthorizedAccounts [GoogleSignInMode.BOTTOM_SHEET] 에서 이 앱에 이미 로그인한 계정만 보일지.
 *   재방문 사용자 자동 로그인에 true, 첫 가입에 false.
 * @property autoSelectEnabled [GoogleSignInMode.BOTTOM_SHEET] 에서 계정이 하나면 묻지 않고 고를지.
 * @throws IllegalArgumentException [serverClientId] 가 `.apps.googleusercontent.com` 으로 끝나는 공백 없는
 *   1..[MAX_CLIENT_ID_LENGTH] 자가 아니거나, [GoogleSignInMode.BUTTON] 인데 시트 전용 선택지를 켰을 때.
 */
public class GoogleLoginConfig(
    public val serverClientId: String,
    public val mode: GoogleSignInMode = GoogleSignInMode.BOTTOM_SHEET,
    public val filterByAuthorizedAccounts: Boolean = false,
    public val autoSelectEnabled: Boolean = false,
) {

    init {
        require(
            serverClientId.length in (CLIENT_ID_SUFFIX.length + 1)..MAX_CLIENT_ID_LENGTH &&
                serverClientId.endsWith(CLIENT_ID_SUFFIX) &&
                serverClientId.none { it.isWhitespace() },
        ) {
            "serverClientId 는 '$CLIENT_ID_SUFFIX' 로 끝나는 공백 없는 ${CLIENT_ID_SUFFIX.length + 1}..$MAX_CLIENT_ID_LENGTH 자 " +
                "웹 클라이언트 ID 여야 한다: 받은 길이 ${serverClientId.length}"
        }
        require(mode == GoogleSignInMode.BOTTOM_SHEET || (!filterByAuthorizedAccounts && !autoSelectEnabled)) {
            "filterByAuthorizedAccounts·autoSelectEnabled 는 BOTTOM_SHEET 에서만 쓴다: " +
                "받은 값 mode=$mode, filterByAuthorizedAccounts=$filterByAuthorizedAccounts, autoSelectEnabled=$autoSelectEnabled"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is GoogleLoginConfig && other.serverClientId == serverClientId && other.mode == mode &&
            other.filterByAuthorizedAccounts == filterByAuthorizedAccounts && other.autoSelectEnabled == autoSelectEnabled

    override fun hashCode(): Int {
        var result = serverClientId.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + filterByAuthorizedAccounts.hashCode()
        result = 31 * result + autoSelectEnabled.hashCode()
        return result
    }

    /** 클라이언트 ID 는 가린다. */
    override fun toString(): String =
        "GoogleLoginConfig(serverClientId=<redacted>, mode=$mode, filterByAuthorizedAccounts=$filterByAuthorizedAccounts, " +
            "autoSelectEnabled=$autoSelectEnabled)"

    public companion object {
        /** [serverClientId] 의 최대 길이. */
        public const val MAX_CLIENT_ID_LENGTH: Int = 256

        /** [serverClientId] 가 끝나야 하는 문자열. */
        public const val CLIENT_ID_SUFFIX: String = ".apps.googleusercontent.com"
    }
}
