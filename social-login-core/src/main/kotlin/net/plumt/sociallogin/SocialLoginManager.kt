package net.plumt.sociallogin

import net.plumt.sociallogin.internal.DefaultSocialLoginManager

/**
 * 등록한 Provider 중에서 요청한 수단을 골라 위임하는 진입점.
 *
 * ```kotlin
 * val manager = SocialLoginManager.builder<Activity>()
 *     .register(KakaoLogin.provider(appContext, KakaoLoginConfig(nativeAppKey = BuildConfig.KAKAO_NATIVE_APP_KEY)))
 *     .register(GoogleLogin.provider(appContext, GoogleLoginConfig(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID)))
 *     .build()
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
 * - 벤더 SDK 가 콜백을 주지 않는 경우에 대비해 [Builder.callTimeoutMillis] 로 호출 시간 상한을 둘 수 있다.
 * - 화면 회전에 살아남는 스코프(`viewModelScope` 등)로 부른다. `lifecycleScope` 로 부르고 회전하면 코루틴만 취소되고
 *   벤더 SDK 는 로그인을 끝내 토큰을 저장할 수 있다 — 앱은 로그아웃 상태로, SDK 는 로그인 상태로 갈라진다.
 * - 상태가 없고 스레드 안전하다. 앱에 하나만 두고 공유한다.
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
        options: SignInOptions = SignInOptions.DEFAULT,
    ): SocialLoginResult<SocialAccount>

    /** [providerId] 의 이 기기 로그인 상태를 지운다. 앱 서버 세션은 앱이 따로 끝낸다. */
    public suspend fun signOut(providerId: ProviderId): SocialLoginResult<Unit>

    /** [providerId] 와 앱의 연결을 끊는다. 앱 서버의 계정 삭제는 앱이 따로 한다. */
    public suspend fun unlink(providerId: ProviderId): SocialLoginResult<Unit>

    /** [providerId] 의 토큰을 갱신한다. */
    public suspend fun refreshToken(providerId: ProviderId): SocialLoginResult<SocialToken>

    /** [SocialLoginManager] 조립기. 스레드 안전하지 않다 — 한 스레드에서 조립하고 [build] 한다. */
    public class Builder<H> internal constructor() {
        private val providers = LinkedHashMap<ProviderId, SocialLoginProvider<H>>()
        private var policy: ConcurrencyPolicy = ConcurrencyPolicy.REJECT
        private var callTimeoutMillis: Long? = null

        /**
         * [provider] 를 등록한다.
         *
         * @throws IllegalArgumentException 같은 [SocialLoginProvider.id] 가 이미 등록돼 있을 때.
         *   나중 것이 앞의 것을 조용히 덮지 않는다.
         */
        public fun register(provider: SocialLoginProvider<H>): Builder<H> {
            require(provider.id !in providers) {
                "같은 수단이 두 번 등록됐다: ${provider.id.value} (이미 등록된 수단 ${providers.keys.map { it.value }})"
            }
            providers[provider.id] = provider
            return this
        }

        /** 동시 호출 정책. 기본 [ConcurrencyPolicy.REJECT]. */
        public fun concurrencyPolicy(policy: ConcurrencyPolicy): Builder<H> {
            this.policy = policy
            return this
        }

        /**
         * Provider 호출 하나의 시간 상한(밀리초). 넘으면 Provider 코루틴을 취소하고 [FailureKind.TIMEOUT] 으로 끝내
         * 잠금을 푼다. 벤더 SDK 가 콜백을 주지 않아 [FailureKind.BUSY] 가 계속되는 것을 막는다. 기본은 상한 없음.
         *
         * 사용자가 계정을 추가하거나 2단계 인증을 거치는 시간까지 넉넉히 잡는다(예: 5분). 시간이 지나도 이미 뜬
         * 벤더 SDK 화면은 닫히지 않는다. [ConcurrencyPolicy.SERIALIZE] 에서 차례를 기다리는 시간은 넣지 않는다.
         *
         * @throws IllegalArgumentException [timeoutMillis] 가 0 이하일 때.
         */
        public fun callTimeoutMillis(timeoutMillis: Long): Builder<H> {
            require(timeoutMillis > 0) { "callTimeoutMillis 는 1 이상이어야 한다: 받은 값 $timeoutMillis" }
            this.callTimeoutMillis = timeoutMillis
            return this
        }

        /** 조립을 끝낸다. Provider 가 하나도 없어도 된다(모든 호출이 [FailureKind.PROVIDER_NOT_REGISTERED]). */
        public fun build(): SocialLoginManager<H> =
            DefaultSocialLoginManager(LinkedHashMap(providers), policy, callTimeoutMillis)
    }

    public companion object {
        /** 새 조립기. */
        @JvmStatic
        public fun <H> builder(): Builder<H> = Builder()
    }
}

/** 한 [SocialLoginManager] 에서 호출이 겹칠 때의 정책. 로그인·로그아웃·연결 해제·갱신 모두에 함께 적용된다. */
public enum class ConcurrencyPolicy {
    /** 진행 중인 호출이 있으면 새 호출을 [FailureKind.BUSY] 로 바로 끝낸다. 기본값. */
    REJECT,

    /** 진행 중인 호출이 끝날 때까지 기다렸다가 차례로 실행한다. 기다리는 동안 취소하면 실행하지 않는다. */
    SERIALIZE,
}
