package net.plumt.sociallogin.google.internal

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
import net.plumt.sociallogin.google.GoogleLoginConfig
import net.plumt.sociallogin.google.GoogleSignInMode
import kotlin.coroutines.cancellation.CancellationException

internal class GoogleLoginProvider(
    private val config: GoogleLoginConfig,
    private val gateway: GoogleGateway,
    private val mainDispatcher: CoroutineDispatcher,
) : SocialLoginProvider<Activity> {

    override val id: ProviderId = ProviderId.GOOGLE

    override val capabilities: Set<Capability> = setOf(Capability.SIGN_OUT)

    override suspend fun signIn(host: Activity, options: SignInOptions): SocialLoginResult<SocialAccount> =
        // 계정 선택 화면을 띄우는 호출은 메인 스레드에서 한다
        withContext(mainDispatcher) {
            call("signIn") { signInWithFallback(host, options.nonce) }
        }

    /**
     * 계정이 많은 기기에서 계정 선택 시트가 Binder 한도(TransactionTooLargeException)에 걸리는 알려진 문제를 피한다.
     * 시트에서 걸리면 버튼 흐름으로 한 번만 다시 시도한다. 그것도 걸리면 전용 vendorCode 의 [FailureKind.UNSUPPORTED].
     */
    private suspend fun signInWithFallback(host: Activity, nonce: String?): SocialLoginResult<SocialAccount> {
        val first = try {
            return GoogleCredentials.toResult(gateway.getCredential(host, GoogleOptions.build(config, nonce)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!GoogleErrors.isTransactionTooLarge(e)) throw e
            e
        }
        if (config.mode == GoogleSignInMode.BUTTON) return GoogleErrors.transactionTooLarge(first)
        try {
            val option = GoogleOptions.build(config, nonce, GoogleSignInMode.BUTTON)
            return GoogleCredentials.toResult(gateway.getCredential(host, option))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !== first) e.addSuppressed(first)
            if (GoogleErrors.isTransactionTooLarge(e)) return GoogleErrors.transactionTooLarge(e)
            throw e
        }
    }

    override suspend fun signOut(): SocialLoginResult<Unit> = call("signOut") {
        gateway.clearCredentialState()
        SocialLoginResult.Success(Unit)
    }

    override suspend fun unlink(): SocialLoginResult<Unit> = SocialLoginResult.Failure(
        kind = FailureKind.UNSUPPORTED,
        message = "Google 연결 해제(권한 철회)는 앱 서버가 Google OAuth 철회 엔드포인트로 한다",
        providerId = id,
    )

    override suspend fun refreshToken(): SocialLoginResult<SocialToken> = SocialLoginResult.Failure(
        kind = FailureKind.UNSUPPORTED,
        message = "Google ID 토큰은 갱신하지 않는다. 다시 로그인해 새 ID 토큰을 받는다",
        providerId = id,
    )

    private suspend fun <T> call(operation: String, block: suspend () -> SocialLoginResult<T>): SocialLoginResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoogleErrors.toResult(e, operation)
        }
}
