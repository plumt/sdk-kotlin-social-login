package net.plumt.sociallogin

/**
 * [SocialLoginManager.create] 에 넘기는 불변 설정. [builder] 로 만든다.
 *
 * @param H 로그인 화면을 붙일 호스트 타입. Android 에서는 `android.app.Activity`.
 */
public class SocialLoginConfig<H> internal constructor(
    internal val providerMap: Map<ProviderId, SocialLoginProvider<H>>,
    /** 동시 호출 정책. 기본 [ConcurrencyPolicy.REJECT]. */
    public val concurrencyPolicy: ConcurrencyPolicy,
    /** Provider 호출 하나의 시간 상한(밀리초). null 이면 상한 없음(기본). */
    public val callTimeoutMillis: Long?,
) {

    /** 등록한 Provider. 등록한 순서다. */
    public val providers: List<SocialLoginProvider<H>> get() = providerMap.values.toList()

    override fun equals(other: Any?): Boolean =
        other is SocialLoginConfig<*> && other.providerMap == providerMap &&
            other.concurrencyPolicy == concurrencyPolicy && other.callTimeoutMillis == callTimeoutMillis

    override fun hashCode(): Int =
        (31 * providerMap.hashCode() + concurrencyPolicy.hashCode()) * 31 + callTimeoutMillis.hashCode()

    override fun toString(): String =
        "SocialLoginConfig(providers=${providerMap.keys.map { it.value }}, concurrencyPolicy=$concurrencyPolicy, " +
            "callTimeoutMillis=$callTimeoutMillis)"

    /** [SocialLoginConfig] 조립기. 스레드 안전하지 않다 — 한 스레드에서 조립하고 [build] 한다. */
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

        /** 조립을 끝낸다. Provider 가 하나도 없어도 된다. */
        public fun build(): SocialLoginConfig<H> = SocialLoginConfig(LinkedHashMap(providers), policy, callTimeoutMillis)
    }

    public companion object {
        /** 새 조립기. */
        @JvmStatic
        public fun <H> builder(): Builder<H> = Builder()
    }
}
