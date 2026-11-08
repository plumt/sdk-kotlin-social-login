package net.plumt.sociallogin.google

import net.plumt.sociallogin.SignInOptions

/** Google 로그인 화면의 형태. */
public enum class GoogleSignInMode {
    /**
     * Credential Manager 의 계정 선택 시트(`GetGoogleIdOption`). 앱 시작 때 조용한 자동 로그인 시도에 쓴다.
     * 계정이 많은 일부 기기에서 시트가 뜨지 못할 수 있다(README 알려진 문제).
     * 연결 해제는 `AuthorizationClient.revokeAccess` 로 한다(0.1.0, [GoogleLogin.unlink] 참고).
     */
    BOTTOM_SHEET,

    /**
     * Credential Manager 의 "Google 계정으로 로그인" 버튼 흐름(`GetSignInWithGoogleOption`). 계정 추가도 된다.
     * [BOTTOM_SHEET] 와 같은 Credential Manager 경로라 같은 알려진 문제의 영향을 받을 수 있다.
     * 연결 해제는 [BOTTOM_SHEET] 와 같다.
     */
    BUTTON,

    /**
     * Google Sign-In 계정 선택 화면(`play-services-auth` 의 `GoogleSignIn` 인텐트)을 투명 호스트 Activity 로 띄운다.
     * **0.0.2 부터 기본값.** 계정이 많은 고밀도 기기에서 Credential Manager 시트가 Binder 전송 한도에 걸려 뜨지 못한
     * 사례를 피하려고, 실기기에서 문제가 없던 이 경로를 기본으로 둔다.
     *
     * - Google 이 지원 중단(deprecated)으로 표시한 API 다. 동작은 하지만 앞으로 제거될 수 있다.
     * - nonce 를 넣을 수 없다. [SignInOptions.nonce] 를 넘기면 [net.plumt.sociallogin.FailureKind.UNSUPPORTED] 다.
     *   nonce 가 필요하면 [BUTTON]·[BOTTOM_SHEET] 를 쓴다.
     * - 연결 해제(`revokeAccess`)를 지원한다.
     */
    LEGACY_GOOGLE_SIGN_IN,
}

/**
 * Google 로그인 설정.
 *
 * @property serverClientId Google Cloud 콘솔(또는 Firebase 콘솔)의 **웹 애플리케이션** OAuth 클라이언트 ID.
 *   Android 클라이언트 ID 를 넣으면 ID 토큰이 나오지 않는다. 앱이 런타임에 넣는다.
 *   ID 토큰의 `aud` 가 이 값이 되므로 서버는 같은 값으로 검증한다.
 *   비었거나 공백뿐이면 **설정 안 됨**([isConfigured] = false)이다. 생성은 되고, 모든 호출이 Google 을 부르지 않은 채
 *   [net.plumt.sociallogin.FailureKind.CONFIGURATION] 으로 끝난다.
 * @property mode 로그인 화면 형태. 기본 [GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN](0.0.2 에서 바뀜). 호출마다 바꾸려면
 *   [GoogleSignInExtension] 을 [SignInOptions] 에 넣는다.
 * @property filterByAuthorizedAccounts [GoogleSignInMode.BOTTOM_SHEET] 에서 이 앱에 이미 로그인한 계정만 보일지.
 *   재방문 사용자 자동 로그인에 true, 첫 가입에 false.
 * @property autoSelectEnabled [GoogleSignInMode.BOTTOM_SHEET] 에서 계정이 하나면 묻지 않고 고를지.
 * @throws IllegalArgumentException [serverClientId] 가 비어 있지 않은데 `.apps.googleusercontent.com` 으로 끝나는 공백 없는
 *   1..[MAX_CLIENT_ID_LENGTH] 자가 아니거나, [GoogleSignInMode.BOTTOM_SHEET] 가 아닌데 시트 전용 선택지를 켰을 때.
 */
public class GoogleLoginConfig(
    public val serverClientId: String,
    public val mode: GoogleSignInMode = GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN,
    public val filterByAuthorizedAccounts: Boolean = false,
    public val autoSelectEnabled: Boolean = false,
) {

    init {
        require(
            serverClientId.isBlank() ||
                (
                    serverClientId.length in (CLIENT_ID_SUFFIX.length + 1)..MAX_CLIENT_ID_LENGTH &&
                        serverClientId.endsWith(CLIENT_ID_SUFFIX) &&
                        serverClientId.none { it.isWhitespace() }
                    ),
        ) {
            "serverClientId 는 '$CLIENT_ID_SUFFIX' 로 끝나는 공백 없는 ${CLIENT_ID_SUFFIX.length + 1}..$MAX_CLIENT_ID_LENGTH 자 " +
                "웹 클라이언트 ID 여야 한다: 받은 길이 ${serverClientId.length}"
        }
        requireSheetOnlyFlags(mode, filterByAuthorizedAccounts, autoSelectEnabled)
    }

    /** 웹 클라이언트 ID 가 있어 Google 을 부를 수 있는지. false 면 모든 호출이 CONFIGURATION 이다. */
    public val isConfigured: Boolean get() = serverClientId.isNotBlank()

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

/**
 * Google 로그인 한 번의 화면 형태를 [GoogleLoginConfig] 대신 정한다. [SignInOptions] 의 `extensions` 에 넣는다.
 *
 * 앱 시작 때 조용한 자동 로그인(시트 + 이미 로그인한 계정만 + 자동 선택)을 해 보고, 실패하면 버튼으로 로그인하는
 * 흐름을 Provider 하나로 한다.
 *
 * ```kotlin
 * val silent = SignInOptions(nonce = null, extensions = listOf(
 *     GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, filterByAuthorizedAccounts = true, autoSelectEnabled = true),
 * ))
 * manager.signIn(ProviderId.GOOGLE, activity, silent)
 * ```
 *
 * @throws IllegalArgumentException [GoogleSignInMode.BOTTOM_SHEET] 가 아닌데 시트 전용 선택지를 켰을 때.
 */
public class GoogleSignInExtension(
    public val mode: GoogleSignInMode,
    public val filterByAuthorizedAccounts: Boolean = false,
    public val autoSelectEnabled: Boolean = false,
) : SignInOptions.Extension {

    init {
        requireSheetOnlyFlags(mode, filterByAuthorizedAccounts, autoSelectEnabled)
    }

    override fun equals(other: Any?): Boolean =
        other is GoogleSignInExtension && other.mode == mode &&
            other.filterByAuthorizedAccounts == filterByAuthorizedAccounts && other.autoSelectEnabled == autoSelectEnabled

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + filterByAuthorizedAccounts.hashCode()
        result = 31 * result + autoSelectEnabled.hashCode()
        return result
    }

    override fun toString(): String =
        "GoogleSignInExtension(mode=$mode, filterByAuthorizedAccounts=$filterByAuthorizedAccounts, " +
            "autoSelectEnabled=$autoSelectEnabled)"
}

private fun requireSheetOnlyFlags(mode: GoogleSignInMode, filterByAuthorizedAccounts: Boolean, autoSelectEnabled: Boolean) {
    require(mode == GoogleSignInMode.BOTTOM_SHEET || (!filterByAuthorizedAccounts && !autoSelectEnabled)) {
        "filterByAuthorizedAccounts·autoSelectEnabled 는 BOTTOM_SHEET 에서만 쓴다: " +
            "받은 값 mode=$mode, filterByAuthorizedAccounts=$filterByAuthorizedAccounts, autoSelectEnabled=$autoSelectEnabled"
    }
}
