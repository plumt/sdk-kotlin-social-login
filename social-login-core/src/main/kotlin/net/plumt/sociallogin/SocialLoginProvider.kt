package net.plumt.sociallogin

/**
 * 소셜 로그인 수단 하나의 구현 계약. 새 수단(Apple·Facebook 등)은 이 인터페이스를 구현한 모듈로 붙인다.
 *
 * @param H 로그인 화면을 띄울 호스트 타입. Android 구현은 `android.app.Activity` 다. 이 모듈은 Android 에
 *   의존하지 않으려고 타입 매개변수로 둔다.
 *
 * ## 구현 규칙
 * - 실패는 예외가 아니라 [SocialLoginResult.Failure] 로 돌려준다. 그래도 예외가 새면 [SocialLoginManager] 가
 *   [SocialLoginErrors.classify] 로 감싼다.
 * - 사용자가 로그인 화면을 닫으면 [SocialLoginResult.Cancelled] 다.
 * - 코루틴 취소(`CancellationException`)는 결과로 바꾸지 않고 다시 던진다.
 * - 토큰·이메일·키 값을 로그나 [SocialLoginResult.Failure.message] 에 넣지 않는다.
 * - 로그인 응답에 없는 정보를 얻으려고 추가 API 를 부르지 않는다([SocialAccount] 참고).
 * - 화면이 필요한 SDK 호출의 스레드 전환은 구현이 책임진다. 호출부는 어느 스레드에서 불러도 된다.
 * - [capabilities] 에 없는 동작은 [SocialLoginManager] 가 부르지 않는다. 직접 부르는 경우를 위해
 *   기본 구현은 [FailureKind.UNSUPPORTED] 를 돌려준다.
 */
public interface SocialLoginProvider<in H> {

    /** 이 구현이 맡는 수단. 한 [SocialLoginManager] 안에서 겹치면 안 된다. */
    public val id: ProviderId

    /** 로그인 말고 지원하는 동작. */
    public val capabilities: Set<Capability>

    /**
     * 로그인 화면을 띄워 로그인한다.
     *
     * @param host 로그인 화면을 붙일 호스트(Android 에서는 포그라운드 Activity).
     */
    public suspend fun signIn(host: H, options: SignInOptions): SocialLoginResult<SocialAccount>

    /** 이 기기의 로그인 상태를 지운다. 기본 구현은 [FailureKind.UNSUPPORTED]. */
    public suspend fun signOut(): SocialLoginResult<Unit> = unsupported(id, Capability.SIGN_OUT)

    /** 앱과 소셜 계정의 연결을 끊는다. 기본 구현은 [FailureKind.UNSUPPORTED]. */
    public suspend fun unlink(): SocialLoginResult<Unit> = unsupported(id, Capability.UNLINK)

    /** 저장된 갱신 토큰으로 토큰을 새로 받는다. 기본 구현은 [FailureKind.UNSUPPORTED]. */
    public suspend fun refreshToken(): SocialLoginResult<SocialToken> = unsupported(id, Capability.REFRESH_TOKEN)

    public companion object {
        /** [providerId] 가 [capability] 를 지원하지 않는다는 실패. 구현이 같은 문구를 쓰도록 공개한다. */
        @JvmStatic
        public fun unsupported(providerId: ProviderId, capability: Capability): SocialLoginResult.Failure =
            SocialLoginResult.Failure(
                kind = FailureKind.UNSUPPORTED,
                message = "${providerId.value} 는 ${capability.name} 를 지원하지 않는다",
                providerId = providerId,
            )
    }
}
