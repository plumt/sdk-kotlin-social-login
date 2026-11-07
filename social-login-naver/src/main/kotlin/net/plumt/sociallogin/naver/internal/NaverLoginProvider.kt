package net.plumt.sociallogin.naver.internal

import android.app.Activity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.plumt.sociallogin.Capability
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialAccount
import net.plumt.sociallogin.SocialLoginProvider
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.SocialToken
import net.plumt.sociallogin.naver.NaverLoginConfig
import kotlin.coroutines.cancellation.CancellationException

internal class NaverLoginProvider(
    private val config: NaverLoginConfig,
    private val gateway: NaverGateway,
    private val mainDispatcher: CoroutineDispatcher,
) : SocialLoginProvider<Activity> {

    override val id: ProviderId = ProviderId.NAVER

    override val capabilities: Set<Capability> = setOf(Capability.SIGN_OUT, Capability.UNLINK, Capability.REFRESH_TOKEN)

    override suspend fun signIn(host: Activity, options: SignInOptions): SocialLoginResult<SocialAccount> {
        if (options.nonce != null) {
            // 네이버는 ID 토큰이 없어 nonce 를 담을 곳이 없다. 조용히 무시하면 서버의 nonce 검증이 엉뚱하게 실패한다
            return SocialLoginResult.Failure(
                kind = FailureKind.UNSUPPORTED,
                message = "네이버 로그인은 nonce 를 지원하지 않는다(ID 토큰이 없다)",
                providerId = id,
            )
        }
        // 로그인 화면을 띄우는 SDK 호출은 메인 스레드여야 한다
        return withContext(mainDispatcher) {
            call("signIn") {
                gateway.authenticate(host)
                val token = currentToken() ?: return@call emptyTokenFailure("signIn")
                SocialLoginResult.Success(SocialAccount(providerId = id, token = token))
            }
        }
    }

    /**
     * 이 기기에 저장된 토큰만 지운다. 네이버 서버의 연동은 남는다([unlink] 와 다르다).
     * 브라우저(커스텀 탭)의 네이버 로그인 세션도 남아, 다음 로그인이 입력 화면 없이 같은 계정으로 끝날 수 있다.
     */
    override suspend fun signOut(): SocialLoginResult<Unit> = call("signOut") {
        gateway.logout()
        SocialLoginResult.Success(Unit)
    }

    /**
     * 네이버 서버의 토큰을 지워 연동을 끊는다.
     *
     * 네이버 SDK 는 서버 응답을 보기 전에 기기 토큰부터 지운다. 그대로 두면 망 오류로 실패한 뒤의 재시도가 저장 토큰이 없어
     * [FailureKind.NOT_SIGNED_IN] 으로 끝나고 서버 연동은 영구히 남는다. 그래서 다시 시도해 볼 만한 실패
     * ([FailureKind.retryable])면 지워진 기기 토큰을 되돌린다 — 다음 호출이 서버를 다시 부른다. 그 밖의 실패는 SDK 동작대로
     * 기기 토큰이 지워진 채로 둔다. 호출 코루틴이 취소되면 SDK 요청은 계속 진행되므로 되돌리지 않는다.
     */
    override suspend fun unlink(): SocialLoginResult<Unit> = call("unlink") {
        val stored = gateway.storedToken()
        if (stored.accessToken.isNullOrBlank() && stored.refreshToken.isNullOrBlank()) {
            return@call notSignedIn("unlink")
        }
        try {
            gateway.deleteToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@call unlinkFailure(e, stored)
        }
        SocialLoginResult.Success(Unit)
    }

    private fun unlinkFailure(error: Exception, stored: NaverStoredToken): SocialLoginResult<Unit> {
        val result = NaverErrors.toResult(error, "unlink")
        if (result !is SocialLoginResult.Failure || !result.kind.retryable) return result
        val now = gateway.storedToken()
        if (!now.accessToken.isNullOrBlank() || !now.refreshToken.isNullOrBlank()) return result
        val note = try {
            gateway.restoreToken(stored)
            "기기 토큰을 되돌렸다 — 다시 시도하면 네이버 서버를 다시 부른다"
        } catch (e: Exception) {
            error.addSuppressed(e)
            "기기 토큰을 되돌리지 못했다 — 다시 로그인한 뒤 연결 해제를 다시 부른다"
        }
        return SocialLoginResult.Failure(
            kind = result.kind,
            message = "${result.message}. $note",
            providerId = result.providerId,
            vendorCode = result.vendorCode,
            cause = result.cause,
        )
    }

    override suspend fun refreshToken(): SocialLoginResult<SocialToken> = call("refreshToken") {
        if (gateway.storedToken().refreshToken.isNullOrBlank()) return@call notSignedIn("refreshToken")
        gateway.refreshAccessToken()
        val token = currentToken() ?: return@call emptyTokenFailure("refreshToken")
        SocialLoginResult.Success(token)
    }

    private fun currentToken(): SocialToken? = toSocialToken(gateway.storedToken())

    private suspend fun <T> call(operation: String, block: suspend () -> SocialLoginResult<T>): SocialLoginResult<T> {
        if (!config.isConfigured) {
            return SocialLoginResult.Failure(
                kind = FailureKind.CONFIGURATION,
                message = "네이버 $operation: clientId·clientSecret·clientName 중 빈 값이 있다(설정 안 됨). SDK 를 부르지 않았다",
                providerId = id,
            )
        }
        try {
            gateway.initialize(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SocialLoginResult.Failure(
                kind = FailureKind.CONFIGURATION,
                message = "네이버 SDK 초기화 실패($operation): ${e.javaClass.name}",
                providerId = id,
                cause = e,
            )
        }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NaverErrors.toResult(e, operation)
        }
    }

    private fun notSignedIn(operation: String) = SocialLoginResult.Failure(
        kind = FailureKind.NOT_SIGNED_IN,
        message = "네이버 $operation: 이 기기에 저장된 토큰이 없다",
        providerId = id,
    )

    private fun emptyTokenFailure(operation: String) = SocialLoginResult.Failure(
        kind = FailureKind.UNKNOWN,
        message = "네이버 $operation 뒤 액세스 토큰이 비어 있다",
        providerId = id,
    )

    internal companion object {
        /** 저장 토큰을 공통 모델로. 액세스 토큰이 없으면 null. 만료 시각은 초 → 밀리초, 0 이하는 "모름". */
        fun toSocialToken(stored: NaverStoredToken): SocialToken? {
            val access = stored.accessToken?.takeIf { it.isNotBlank() } ?: return null
            val expiresAtMillis = stored.expiresAtSeconds.takeIf { it in 1..(Long.MAX_VALUE / 1000) }?.times(1000)
            return SocialToken(
                accessToken = access,
                idToken = null,
                refreshToken = stored.refreshToken?.takeIf { it.isNotBlank() },
                accessTokenExpiresAtMillis = expiresAtMillis,
            )
        }
    }
}
