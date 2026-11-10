package net.plumt.sociallogin

import net.plumt.sociallogin.internal.deliverNow
import java.util.concurrent.Executor

/**
 * 앱 단위 테스트용 [SocialLoginManager]. 벤더 SDK·기기 없이 정해 둔 결과를 돌려주고 호출을 기록한다.
 *
 * ```kotlin
 * val fake = FakeSocialLoginManager<Activity>(listOf(ProviderId.KAKAO))
 * fake.setSignInResult(ProviderId.KAKAO, SocialLoginResult.Success(account))
 * val viewModel = LoginViewModel(fake)
 * // ...
 * assertEquals(FakeSocialLoginManager.SIGN_IN, fake.calls.single().operation)
 * ```
 *
 * - 등록하지 않은 수단은 실제 Manager 와 같이 [FailureKind.PROVIDER_NOT_REGISTERED], 지원하지 않는 동작은
 *   [FailureKind.UNSUPPORTED] 다. 등록한 수단은 기본으로 모든 [Capability] 를 지원한다([setCapabilities] 로 바꾼다).
 * - 결과를 정하지 않은 동작은 [FailureKind.UNKNOWN] 실패다 — 테스트가 결과를 빠뜨린 것을 숨기지 않는다.
 * - 동시 호출 정책·시간 상한은 흉내 내지 않는다. 결과는 곧바로 돌아온다.
 * - 콜백 형태는 코루틴 없이 곧바로 [Executor] 에 결과를 넘긴다. 테스트에서는 `Executor { it.run() }` 를 넘기면 동기로 받는다.
 * - 스레드 안전하다.
 *
 * @param providerIds 등록한 것으로 볼 수단. 순서가 [SocialLoginManager.providerIds] 가 된다.
 * @throws IllegalArgumentException [providerIds] 에 같은 수단이 둘 이상일 때.
 */
public class FakeSocialLoginManager<H>(providerIds: List<ProviderId>) : SocialLoginManager<H> {

    private val lock = Any()
    private val capabilities = LinkedHashMap<ProviderId, Set<Capability>>()
    private val signInResults = HashMap<ProviderId, SocialLoginResult<SocialAccount>>()
    private val signOutResults = HashMap<ProviderId, SocialLoginResult<Unit>>()
    private val unlinkResults = HashMap<ProviderId, SocialLoginResult<Unit>>()
    private val refreshResults = HashMap<ProviderId, SocialLoginResult<SocialToken>>()
    private val recorded = ArrayList<Call>()

    init {
        require(providerIds.distinct().size == providerIds.size) {
            "providerIds 에 같은 수단이 둘 이상이다: 받은 값 ${providerIds.map { it.value }}"
        }
        providerIds.forEach { capabilities[it] = ALL_CAPABILITIES }
    }

    override val providerIds: List<ProviderId> = providerIds.toList()

    /** 지금까지 받은 호출. 부른 순서다. 등록하지 않은 수단·지원하지 않는 동작의 호출도 기록한다. */
    public val calls: List<Call> get() = synchronized(lock) { recorded.toList() }

    /** [calls] 를 비운다. */
    public fun clearCalls() {
        synchronized(lock) { recorded.clear() }
    }

    /**
     * [providerId] 가 지원하는 동작을 바꾼다.
     *
     * @throws IllegalArgumentException [providerId] 가 등록돼 있지 않을 때.
     */
    public fun setCapabilities(providerId: ProviderId, capabilities: Set<Capability>) {
        synchronized(lock) {
            requireRegistered(providerId)
            this.capabilities[providerId] = capabilities.toSet()
        }
    }

    /** [providerId] 의 로그인 결과를 정한다. @throws IllegalArgumentException [providerId] 가 등록돼 있지 않을 때. */
    public fun setSignInResult(providerId: ProviderId, result: SocialLoginResult<SocialAccount>) {
        put(signInResults, providerId, result)
    }

    /** [providerId] 의 로그아웃 결과를 정한다. @throws IllegalArgumentException [providerId] 가 등록돼 있지 않을 때. */
    public fun setSignOutResult(providerId: ProviderId, result: SocialLoginResult<Unit>) {
        put(signOutResults, providerId, result)
    }

    /** [providerId] 의 연결 해제 결과를 정한다. @throws IllegalArgumentException [providerId] 가 등록돼 있지 않을 때. */
    public fun setUnlinkResult(providerId: ProviderId, result: SocialLoginResult<Unit>) {
        put(unlinkResults, providerId, result)
    }

    /** [providerId] 의 토큰 갱신 결과를 정한다. @throws IllegalArgumentException [providerId] 가 등록돼 있지 않을 때. */
    public fun setRefreshTokenResult(providerId: ProviderId, result: SocialLoginResult<SocialToken>) {
        put(refreshResults, providerId, result)
    }

    override fun isRegistered(providerId: ProviderId): Boolean = synchronized(lock) { providerId in capabilities }

    override fun capabilitiesOf(providerId: ProviderId): Set<Capability> =
        synchronized(lock) { capabilities[providerId] ?: emptySet() }

    override suspend fun signIn(
        providerId: ProviderId,
        host: H,
        options: SignInOptions,
    ): SocialLoginResult<SocialAccount> = answer(SIGN_IN, providerId, options, null, signInResults)

    override suspend fun signOut(providerId: ProviderId): SocialLoginResult<Unit> =
        answer(SIGN_OUT, providerId, null, Capability.SIGN_OUT, signOutResults)

    override suspend fun unlink(providerId: ProviderId): SocialLoginResult<Unit> =
        answer(UNLINK, providerId, null, Capability.UNLINK, unlinkResults)

    override suspend fun refreshToken(providerId: ProviderId): SocialLoginResult<SocialToken> =
        answer(REFRESH_TOKEN, providerId, null, Capability.REFRESH_TOKEN, refreshResults)

    override fun signIn(
        providerId: ProviderId,
        host: H,
        options: SignInOptions,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<SocialAccount>,
    ): SocialLoginCallHandle =
        deliverNow(callbackExecutor, callback, answer(SIGN_IN, providerId, options, null, signInResults))

    override fun signOut(
        providerId: ProviderId,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<Unit>,
    ): SocialLoginCallHandle =
        deliverNow(callbackExecutor, callback, answer(SIGN_OUT, providerId, null, Capability.SIGN_OUT, signOutResults))

    override fun unlink(
        providerId: ProviderId,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<Unit>,
    ): SocialLoginCallHandle =
        deliverNow(callbackExecutor, callback, answer(UNLINK, providerId, null, Capability.UNLINK, unlinkResults))

    override fun refreshToken(
        providerId: ProviderId,
        callbackExecutor: Executor,
        callback: SocialLoginCallback<SocialToken>,
    ): SocialLoginCallHandle = deliverNow(
        callbackExecutor,
        callback,
        answer(REFRESH_TOKEN, providerId, null, Capability.REFRESH_TOKEN, refreshResults),
    )

    override fun toString(): String = "FakeSocialLoginManager(providerIds=${providerIds.map { it.value }})"

    private fun <T> put(map: MutableMap<ProviderId, SocialLoginResult<T>>, providerId: ProviderId, result: SocialLoginResult<T>) {
        synchronized(lock) {
            requireRegistered(providerId)
            map[providerId] = result
        }
    }

    private fun requireRegistered(providerId: ProviderId) {
        require(providerId in capabilities) {
            "등록하지 않은 수단이다: ${providerId.value} (등록된 수단 ${providerIds.map { it.value }})"
        }
    }

    private fun <T> answer(
        operation: String,
        providerId: ProviderId,
        options: SignInOptions?,
        capability: Capability?,
        results: Map<ProviderId, SocialLoginResult<T>>,
    ): SocialLoginResult<T> = synchronized(lock) {
        recorded += Call(operation, providerId, options)
        val supported = capabilities[providerId]
            ?: return SocialLoginResult.Failure(
                kind = FailureKind.PROVIDER_NOT_REGISTERED,
                message = "등록되지 않은 수단: ${providerId.value} (등록된 수단 ${providerIds.map { it.value }})",
                providerId = providerId,
                vendorCode = null,
                cause = null,
            )
        if (capability != null && capability !in supported) return SocialLoginProvider.unsupported(providerId, capability)
        results[providerId] ?: SocialLoginResult.Failure(
            kind = FailureKind.UNKNOWN,
            message = "FakeSocialLoginManager 에 ${providerId.value} 의 $operation 결과를 정하지 않았다",
            providerId = providerId,
            vendorCode = null,
            cause = null,
        )
    }

    /**
     * 기록된 호출 하나.
     *
     * @property operation [SIGN_IN]·[SIGN_OUT]·[UNLINK]·[REFRESH_TOKEN] 중 하나.
     * @property providerId 호출한 수단.
     * @property options 로그인 호출이면 넘긴 선택 사항, 아니면 null.
     */
    public class Call(
        public val operation: String,
        public val providerId: ProviderId,
        public val options: SignInOptions?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Call && other.operation == operation && other.providerId == providerId && other.options == options

        override fun hashCode(): Int = (31 * operation.hashCode() + providerId.hashCode()) * 31 + options.hashCode()

        override fun toString(): String = "Call(operation=$operation, providerId=${providerId.value}, options=$options)"
    }

    public companion object {
        /** [Call.operation] — 로그인. */
        public const val SIGN_IN: String = "signIn"

        /** [Call.operation] — 로그아웃. */
        public const val SIGN_OUT: String = "signOut"

        /** [Call.operation] — 연결 해제. */
        public const val UNLINK: String = "unlink"

        /** [Call.operation] — 토큰 갱신. */
        public const val REFRESH_TOKEN: String = "refreshToken"

        private val ALL_CAPABILITIES: Set<Capability> = Capability.entries.toSet()
    }
}
