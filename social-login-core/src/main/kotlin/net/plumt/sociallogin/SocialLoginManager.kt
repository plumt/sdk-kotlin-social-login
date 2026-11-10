package net.plumt.sociallogin

import net.plumt.sociallogin.internal.DefaultSocialLoginManager
import net.plumt.sociallogin.internal.launchCallback
import java.util.concurrent.Executor

/**
 * 등록한 Provider 중에서 요청한 수단을 골라 위임하는 진입점.
 *
 * ```kotlin
 * val manager = SocialLoginManager.create(
 *     SocialLoginConfig.builder<Activity>()
 *         .register(KakaoLogin.provider(appContext, KakaoLoginConfig.builder(BuildConfig.KAKAO_NATIVE_APP_KEY).build()))
 *         .register(GoogleLogin.provider(appContext, GoogleLoginConfig.builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()))
 *         .build(),
 * )
 *
 * when (val result = manager.signIn(ProviderId.KAKAO, activity)) {
 *     is SocialLoginResult.Success -> sendToServer(result.value.token)  // 서버가 토큰을 검증한다
 *     SocialLoginResult.Cancelled -> Unit
 *     is SocialLoginResult.Failure -> showError(result.kind)
 * }
 * ```
 *
 * - 등록하지 않은 수단은 [FailureKind.PROVIDER_NOT_REGISTERED], Provider 가 선언하지 않은 동작은
 *   [FailureKind.UNSUPPORTED] 로 끝난다. 둘 다 Provider 를 부르지 않는다.
 * - Provider 가 예외를 던지면 [SocialLoginErrors.classify] 로 감싼 [SocialLoginResult.Failure] 가 된다.
 *   코루틴 취소만은 그대로 다시 던진다.
 * - 동시 호출은 [ConcurrencyPolicy] 로 정한다. 기본은 [ConcurrencyPolicy.REJECT] — 진행 중인 호출이 있으면
 *   새 호출을 [FailureKind.BUSY] 로 바로 끝낸다. 로그인 버튼을 두 번 눌러 로그인 화면이 두 번 뜨는 것을 막는다.
 * - 스레드를 바꾸지 않는다. 어느 스레드에서 불러도 되고, 화면이 필요한 SDK 호출의 스레드 전환은 Provider 가 한다.
 * - 벤더 SDK 가 콜백을 주지 않는 경우에 대비해 [SocialLoginConfig.Builder.callTimeoutMillis] 로 호출 시간 상한을 둘 수 있다.
 * - 화면 회전에 살아남는 스코프(`viewModelScope` 등)로 부른다. `lifecycleScope` 로 부르고 회전하면 코루틴만 취소되고
 *   벤더 SDK 는 로그인을 끝내 토큰을 저장할 수 있다 — 앱은 로그아웃 상태로, SDK 는 로그인 상태로 갈라진다.
 * - 상태가 없고 스레드 안전하다. 앱에 하나만 두고 공유한다.
 * - 같은 동작을 `suspend` 함수(기준)와 콜백 오버로드(결과를 받을 `Executor` 와 [SocialLoginCallback], 취소 핸들
 *   [SocialLoginCallHandle] 반환)로 준다. 콜백 형태는 `suspend` 형태를 감쌀 뿐 로직이 같다.
 * - 기기 없는 앱 단위 테스트에서는 [FakeSocialLoginManager] 로 바꾼다.
 *
 * @param H 로그인 화면을 붙일 호스트 타입. Android 에서는 `android.app.Activity`.
 */
public interface SocialLoginManager<in H> {

    /** 등록한 수단. 등록한 순서다. 화면은 이 목록으로 버튼을 그린다. */
    public val providerIds: List<ProviderId>

    /** [providerId] 가 등록돼 있는지. */
    public fun isRegistered(providerId: ProviderId): Boolean

    /** [providerId] 가 지원하는 동작. 등록하지 않은 수단이면 빈 집합. */
    public fun capabilitiesOf(providerId: ProviderId): Set<Capability>

    /** [providerId] 로 로그인한다. 로그인 화면은 [host] 위에 뜬다. */
    public suspend fun signIn(
        providerId: ProviderId,
        host: H,
        options: SignInOptions,
    ): SocialLoginResult<SocialAccount>

    /** 선택 사항 없이([SignInOptions.DEFAULT]) [providerId] 로 로그인한다. */
    public suspend fun signIn(providerId: ProviderId, host: H): SocialLoginResult<SocialAccount> =
        signIn(providerId, host, SignInOptions.DEFAULT)

    /** [providerId] 의 이 기기 로그인 상태를 지운다. 앱 서버 세션은 앱이 따로 끝낸다. */
    public suspend fun signOut(providerId: ProviderId): SocialLoginResult<Unit>

    /** [providerId] 와 앱의 연결을 끊는다. 앱 서버의 계정 삭제는 앱이 따로 한다. */
    public suspend fun unlink(providerId: ProviderId): SocialLoginResult<Unit>

    /** [providerId] 의 토큰을 갱신한다. */
    public suspend fun refreshToken(providerId: ProviderId): SocialLoginResult<SocialToken>

    /**
     * [signIn] 의 콜백 형태. 같은 `suspend` [signIn] 을 호출마다 새 코루틴에서 돌리고 결과를 [callbackExecutor] 에서
     * [callback] 으로 한 번 준다. Android 에서는 메인 스레드 실행기(`ContextCompat.getMainExecutor(context)`)를 넘긴다.
     *
     * 코루틴은 화면 수명과 무관해 회전으로 취소되지 않는다. 화면이 사라질 때 [SocialLoginCallHandle.cancel] 하면 콜백은
     * 불리지 않지만 이미 뜬 벤더 SDK 화면은 로그인을 끝낼 수 있다 — 회전에 살아남는 곳(ViewModel 등)에서 부르고 핸들을 둔다.
     * 프로세스가 종료되면 콜백은 불리지 않는다.
     *
     * @return 취소 핸들.
     */
    public fun signIn(
        providerId: ProviderId,
        host: H,
        options: SignInOptions,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<SocialAccount>,
    ): SocialLoginCallHandle = launchCallback(callbackExecutor, callback) { signIn(providerId, host, options) }

    /** [signOut] 의 콜백 형태. 결과 전달·취소는 콜백 [signIn] 과 같다. */
    public fun signOut(
        providerId: ProviderId,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<Unit>,
    ): SocialLoginCallHandle = launchCallback(callbackExecutor, callback) { signOut(providerId) }

    /** [unlink] 의 콜백 형태. 결과 전달·취소는 콜백 [signIn] 과 같다. */
    public fun unlink(
        providerId: ProviderId,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<Unit>,
    ): SocialLoginCallHandle = launchCallback(callbackExecutor, callback) { unlink(providerId) }

    /** [refreshToken] 의 콜백 형태. 결과 전달·취소는 콜백 [signIn] 과 같다. */
    public fun refreshToken(
        providerId: ProviderId,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<SocialToken>,
    ): SocialLoginCallHandle = launchCallback(callbackExecutor, callback) { refreshToken(providerId) }

    public companion object {
        /**
         * [config] 로 Manager 를 만든다. 가벼운 호출이다. 앱에 하나 두고 DI 로 넘긴다.
         * Provider 가 하나도 없어도 된다(모든 호출이 [FailureKind.PROVIDER_NOT_REGISTERED]).
         */
        @JvmStatic
        public fun <H> create(config: SocialLoginConfig<H>): SocialLoginManager<H> =
            DefaultSocialLoginManager(config.providerMap, config.concurrencyPolicy, config.callTimeoutMillis)
    }
}
