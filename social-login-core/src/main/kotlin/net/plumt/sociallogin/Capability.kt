package net.plumt.sociallogin

/**
 * Provider 가 로그인 말고 더 지원하는 동작.
 *
 * 로그인은 모든 Provider 가 지원하므로 여기 없다. [SocialLoginManager] 는 Provider 가 선언하지 않은
 * 동작을 Provider 를 부르지 않고 [FailureKind.UNSUPPORTED] 로 끝낸다.
 */
public enum class Capability {
    /** 이 기기의 로그인 상태를 지운다. 계정 연결은 남는다. */
    SIGN_OUT,

    /** 앱과 소셜 계정의 연결을 끊는다(회원 탈퇴 시). */
    UNLINK,

    /** 저장된 갱신 토큰으로 액세스 토큰을 새로 받는다. */
    REFRESH_TOKEN,
}
