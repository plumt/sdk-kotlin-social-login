package net.plumt.sociallogin.kakao.internal

import android.app.Activity
import com.kakao.sdk.auth.model.OAuthToken
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
import net.plumt.sociallogin.kakao.KakaoLoginConfig
import kotlin.coroutines.cancellation.CancellationException

internal class KakaoLoginProvider(
    private val config: KakaoLoginConfig,
    private val gateway: KakaoGateway,
    private val mainDispatcher: CoroutineDispatcher,
) : SocialLoginProvider<Activity> {

    override val id: ProviderId = ProviderId.KAKAO

    override val capabilities: Set<Capability> = setOf(Capability.SIGN_OUT, Capability.UNLINK, Capability.REFRESH_TOKEN)

    override suspend fun signIn(host: Activity, options: SignInOptions): SocialLoginResult<SocialAccount> =
        // 로그인 화면을 띄우는 SDK 호출은 메인 스레드여야 한다
        withContext(mainDispatcher) {
            call("signIn") {
                val token = login(host, options.nonce)
                val socialToken = toSocialToken(token) ?: return@call emptyTokenFailure("signIn")
                SocialLoginResult.Success(SocialAccount(providerId = id, token = socialToken))
            }
        }

    override suspend fun signOut(): SocialLoginResult<Unit> = call("signOut") {
        gateway.logout()
        SocialLoginResult.Success(Unit)
    }

    override suspend fun unlink(): SocialLoginResult<Unit> = call("unlink") {
        gateway.unlink()
        SocialLoginResult.Success(Unit)
    }

    override suspend fun refreshToken(): SocialLoginResult<SocialToken> = call("refreshToken") {
        val socialToken = toSocialToken(gateway.refreshToken()) ?: return@call emptyTokenFailure("refreshToken")
        SocialLoginResult.Success(socialToken)
    }

    /** 카카오톡 로그인 → (취소가 아닌 실패면) 카카오계정 로그인. 실패는 SDK 예외 그대로 던진다. */
    private suspend fun login(host: Activity, nonce: String?): OAuthToken {
        if (!config.useKakaoTalk || !gateway.isKakaoTalkLoginAvailable(host)) {
            return gateway.loginWithKakaoAccount(host, nonce)
        }
        val talkError = try {
            return gateway.loginWithKakaoTalk(host, nonce)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 사용자가 카카오톡에서 취소했으면 방금 닫은 로그인 화면을 웹으로 다시 띄우지 않는다
            if (KakaoErrors.isUserCancel(e)) throw e
            e
        }
        try {
            return gateway.loginWithKakaoAccount(host, nonce)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 웹까지 실패하면 카카오톡 로그인 실패 원인도 함께 남긴다
            if (e !== talkError) e.addSuppressed(talkError)
            throw e
        }
    }

    /** SDK 를 초기화하고 [block] 을 실행한다. 예외는 결과로 바꾸고, 코루틴 취소만 다시 던진다. */
    private suspend fun <T> call(operation: String, block: suspend () -> SocialLoginResult<T>): SocialLoginResult<T> {
        try {
            gateway.initialize(config.nativeAppKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SocialLoginResult.Failure(
                kind = FailureKind.CONFIGURATION,
                message = "카카오 SDK 초기화 실패($operation): ${e.javaClass.name}",
                providerId = id,
                cause = e,
            )
        }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KakaoErrors.toResult(e, operation)
        }
    }

    private fun emptyTokenFailure(operation: String) = SocialLoginResult.Failure(
        kind = FailureKind.UNKNOWN,
        message = "카카오 $operation 응답의 액세스 토큰이 비어 있다",
        providerId = id,
    )

    internal companion object {
        /** SDK 토큰을 공통 모델로. 액세스 토큰이 비면 null. 빈 ID·갱신 토큰과 빈 scope 는 "없음" 으로 본다. */
        fun toSocialToken(token: OAuthToken): SocialToken? {
            if (token.accessToken.isBlank()) return null
            return SocialToken(
                accessToken = token.accessToken,
                idToken = token.idToken?.takeIf { it.isNotBlank() },
                refreshToken = token.refreshToken.takeIf { it.isNotBlank() },
                accessTokenExpiresAtMillis = token.accessTokenExpiresAt.time.takeIf { it >= 0 },
                refreshTokenExpiresAtMillis = token.refreshTokenExpiresAt.time.takeIf { it >= 0 },
                scopes = token.scopes.orEmpty().filter { it.isNotBlank() }.toSet(),
            )
        }
    }
}
