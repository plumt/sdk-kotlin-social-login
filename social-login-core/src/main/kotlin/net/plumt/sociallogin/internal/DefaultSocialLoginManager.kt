package net.plumt.sociallogin.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.ConcurrencyPolicy
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialAccount
import net.plumt.sociallogin.SocialLoginErrors
import net.plumt.sociallogin.SocialLoginManager
import net.plumt.sociallogin.SocialLoginProvider
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.SocialToken
import kotlin.coroutines.cancellation.CancellationException

internal class DefaultSocialLoginManager<H>(
    private val providers: Map<ProviderId, SocialLoginProvider<H>>,
    private val policy: ConcurrencyPolicy,
    private val callTimeoutMillis: Long? = null,
) : SocialLoginManager<H> {

    private val mutex = Mutex()

    override val providerIds: List<ProviderId> = providers.keys.toList()

    override fun isRegistered(providerId: ProviderId): Boolean = providerId in providers

    override fun capabilitiesOf(providerId: ProviderId): Set<Capability> =
        providers[providerId]?.capabilities?.toSet() ?: emptySet()

    override suspend fun signIn(
        providerId: ProviderId,
        host: H,
        options: SignInOptions,
    ): SocialLoginResult<SocialAccount> = run(providerId, operation = "signIn", capability = null) { provider ->
        val result = provider.signIn(host, options)
        val account = (result as? SocialLoginResult.Success)?.value
        if (account != null && account.providerId != providerId) {
            SocialLoginResult.Failure(
                kind = FailureKind.UNKNOWN,
                message = "Provider ${providerId.value} 가 다른 수단(${account.providerId.value})의 계정을 돌려줬다",
                providerId = providerId,
            )
        } else {
            result
        }
    }

    override suspend fun signOut(providerId: ProviderId): SocialLoginResult<Unit> =
        run(providerId, operation = "signOut", capability = Capability.SIGN_OUT) { it.signOut() }

    override suspend fun unlink(providerId: ProviderId): SocialLoginResult<Unit> =
        run(providerId, operation = "unlink", capability = Capability.UNLINK) { it.unlink() }

    override suspend fun refreshToken(providerId: ProviderId): SocialLoginResult<SocialToken> =
        run(providerId, operation = "refreshToken", capability = Capability.REFRESH_TOKEN) { it.refreshToken() }

    private suspend fun <T> run(
        providerId: ProviderId,
        operation: String,
        capability: Capability?,
        block: suspend (SocialLoginProvider<H>) -> SocialLoginResult<T>,
    ): SocialLoginResult<T> {
        val provider = providers[providerId]
            ?: return SocialLoginResult.Failure(
                kind = FailureKind.PROVIDER_NOT_REGISTERED,
                message = "등록되지 않은 수단: ${providerId.value} (등록된 수단 ${providerIds.map { it.value }})",
                providerId = providerId,
            )
        if (capability != null && capability !in provider.capabilities) {
            return SocialLoginProvider.unsupported(providerId, capability)
        }
        return when (policy) {
            ConcurrencyPolicy.SERIALIZE -> mutex.withLock { invoke(provider, providerId, operation, block) }
            ConcurrencyPolicy.REJECT -> {
                if (!mutex.tryLock()) {
                    return SocialLoginResult.Failure(
                        kind = FailureKind.BUSY,
                        message = "다른 소셜 로그인 호출이 진행 중이라 $operation(${providerId.value}) 을 거절했다",
                        providerId = providerId,
                    )
                }
                try {
                    invoke(provider, providerId, operation, block)
                } finally {
                    mutex.unlock()
                }
            }
        }
    }

    private suspend fun <T> invoke(
        provider: SocialLoginProvider<H>,
        providerId: ProviderId,
        operation: String,
        block: suspend (SocialLoginProvider<H>) -> SocialLoginResult<T>,
    ): SocialLoginResult<T> {
        val result = try {
            if (callTimeoutMillis == null) {
                block(provider)
            } else {
                // 상한을 넘으면 Provider 코루틴을 취소한다. 잠금은 호출부의 finally 가 푼다
                withTimeoutOrNull(callTimeoutMillis) { block(provider) }
                    ?: return SocialLoginResult.Failure(
                        kind = FailureKind.TIMEOUT,
                        message = "Provider ${providerId.value} 의 $operation 이 ${callTimeoutMillis}ms 안에 끝나지 않아 " +
                            "중단했다. 이미 뜬 벤더 SDK 화면은 남아 있을 수 있다",
                        providerId = providerId,
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SocialLoginErrors.failureOf(
                throwable = e,
                message = "Provider ${providerId.value} 의 $operation 이 예외를 던졌다: ${e.javaClass.name}",
                providerId = providerId,
            )
        }
        return if (result is SocialLoginResult.Failure && result.providerId == null) {
            result.withProviderId(providerId)
        } else {
            result
        }
    }
}
