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
import net.plumt.sociallogin.google.GoogleSignInExtension
import net.plumt.sociallogin.google.GoogleSignInMode
import kotlin.coroutines.cancellation.CancellationException

internal class GoogleLoginProvider(
    private val config: GoogleLoginConfig,
    private val gateway: GoogleGateway,
    private val mainDispatcher: CoroutineDispatcher,
) : SocialLoginProvider<Activity> {

    override val id: ProviderId = ProviderId.GOOGLE

    /** 기본 모드는 Google Sign-In 의 권한 철회로 연결 해제를 지원한다. Credential Manager 모드에는 그런 API 가 없다. */
    override val capabilities: Set<Capability> =
        if (config.mode == GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN) {
            setOf(Capability.SIGN_OUT, Capability.UNLINK)
        } else {
            setOf(Capability.SIGN_OUT)
        }

    override suspend fun signIn(host: Activity, options: SignInOptions): SocialLoginResult<SocialAccount> {
        notConfigured("signIn")?.let { return it }
        val request = GoogleRequest.of(config, options.extension<GoogleSignInExtension>())
        if (request.mode == GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN && options.nonce != null) {
            // Google Sign-In 은 ID 토큰에 nonce 를 넣을 수 없다. 조용히 무시하면 서버의 nonce 검증이 엉뚱하게 실패한다
            return SocialLoginResult.Failure(
                kind = FailureKind.UNSUPPORTED,
                message = "Google 기본 모드(LEGACY_GOOGLE_SIGN_IN)는 nonce 를 지원하지 않는다 — nonce 가 필요하면 " +
                    "BUTTON·BOTTOM_SHEET 모드를 쓴다",
                providerId = id,
            )
        }
        // 계정 선택 화면을 띄우는 호출은 메인 스레드에서 한다
        return withContext(mainDispatcher) {
            call("signIn") {
                if (request.mode == GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN) {
                    legacySignIn(host)
                } else {
                    signInWithFallback(host, options.nonce, request)
                }
            }
        }
    }

    /** Google Sign-In 계정 선택 화면을 투명 호스트로 띄운다. 실패는 예외로 던져 [call] 이 분류한다. */
    private suspend fun legacySignIn(host: Activity): SocialLoginResult<SocialAccount> {
        val intent = gateway.legacySignInIntent(host, config.serverClientId)
        return when (val outcome = gateway.launchHost(host, intent)) {
            GoogleSignInHostResult.Busy -> SocialLoginResult.Failure(
                kind = FailureKind.BUSY,
                message = "다른 Google 로그인 화면이 이미 떠 있다",
                providerId = id,
                vendorCode = VENDOR_HOST_BUSY,
            )
            GoogleSignInHostResult.HostGone -> SocialLoginResult.Cancelled
            GoogleSignInHostResult.NotStarted -> SocialLoginResult.Failure(
                kind = FailureKind.INTERRUPTED,
                message = "Google 계정 선택 화면을 띄울 호스트 Activity 가 " +
                    "${GoogleSignInHost.DEFAULT_START_TIMEOUT_MILLIS}ms 안에 뜨지 않았다 — 화면이 포그라운드일 때 다시 시도한다",
                providerId = id,
                vendorCode = VENDOR_HOST_NOT_STARTED,
            )
            is GoogleSignInHostResult.Failed -> throw outcome.error
            is GoogleSignInHostResult.ActivityResult -> {
                // 실패도 RESULT_CANCELED 와 함께 결과 데이터(상태 코드)로 온다. 데이터가 있으면 먼저 해석해 설정 오류를 취소로 묻지 않는다
                val data = outcome.data
                when {
                    data != null -> GoogleLegacy.toResult(gateway.legacyAccountFrom(data))
                    outcome.resultCode == Activity.RESULT_OK -> SocialLoginResult.Failure(
                        kind = FailureKind.UNKNOWN,
                        message = "Google 계정 선택 화면이 결과 데이터 없이 성공을 돌려줬다",
                        providerId = id,
                    )
                    else -> SocialLoginResult.Cancelled
                }
            }
        }
    }

    /**
     * Credential Manager 모드. 계정이 많은 기기에서 계정 선택 시트가 Binder 한도(TransactionTooLargeException)에 걸리면
     * 버튼 흐름으로 한 번만 다시 시도한다. 그것도 걸리면 전용 vendorCode 의 [FailureKind.UNSUPPORTED].
     */
    private suspend fun signInWithFallback(
        host: Activity,
        nonce: String?,
        request: GoogleRequest,
    ): SocialLoginResult<SocialAccount> {
        val first = try {
            return GoogleCredentials.toResult(gateway.getCredential(host, GoogleOptions.build(config.serverClientId, request, nonce)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!GoogleErrors.isTransactionTooLarge(e)) throw e
            e
        }
        if (request.mode == GoogleSignInMode.BUTTON) return GoogleErrors.transactionTooLarge(first)
        try {
            val option = GoogleOptions.build(config.serverClientId, request.withMode(GoogleSignInMode.BUTTON), nonce)
            return GoogleCredentials.toResult(gateway.getCredential(host, option))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !== first) e.addSuppressed(first)
            if (GoogleErrors.isTransactionTooLarge(e)) return GoogleErrors.transactionTooLarge(e)
            throw e
        }
    }

    /**
     * 이 기기의 로그인 상태를 지운다. 설정한 모드의 상태(기본: Google Sign-In, 그 밖: Credential Manager)를 지우고, 그
     * 실패만 결과로 알린다. 호출별 모드([GoogleSignInExtension])로 다른 경로를 썼을 수 있어 반대쪽 상태도 지워 보되,
     * 그쪽은 기기에 해당 기능이 없을 수 있어 실패를 결과에 넣지 않는다.
     */
    override suspend fun signOut(): SocialLoginResult<Unit> = notConfigured("signOut") ?: call("signOut") {
        if (config.mode == GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN) {
            gateway.legacySignOut(config.serverClientId)
            bestEffort { gateway.clearCredentialState() }
        } else {
            gateway.clearCredentialState()
            bestEffort { gateway.legacySignOut(config.serverClientId) }
        }
        SocialLoginResult.Success(Unit)
    }

    /**
     * 기본 모드: Google 서버에서 이 앱에 준 권한을 철회(`revokeAccess`)하고 기기 로그인 상태를 지운다. 로그인한 계정이 없으면
     * [FailureKind.NOT_SIGNED_IN]. Credential Manager 모드: [FailureKind.UNSUPPORTED] — 철회는 앱 서버가 Google OAuth 철회
     * 엔드포인트로 한다.
     */
    override suspend fun unlink(): SocialLoginResult<Unit> {
        notConfigured("unlink")?.let { return it }
        if (config.mode != GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN) {
            return SocialLoginResult.Failure(
                kind = FailureKind.UNSUPPORTED,
                message = "Google Credential Manager 모드의 연결 해제(권한 철회)는 앱 서버가 Google OAuth 철회 엔드포인트로 한다",
                providerId = id,
            )
        }
        return call("unlink") {
            gateway.legacyRevokeAccess(config.serverClientId)
            bestEffort { gateway.clearCredentialState() }
            SocialLoginResult.Success(Unit)
        }
    }

    override suspend fun refreshToken(): SocialLoginResult<SocialToken> = SocialLoginResult.Failure(
        kind = FailureKind.UNSUPPORTED,
        message = "Google ID 토큰은 갱신하지 않는다. 다시 로그인해 새 ID 토큰을 받는다",
        providerId = id,
    )

    private fun notConfigured(operation: String): SocialLoginResult.Failure? =
        if (config.isConfigured) {
            null
        } else {
            SocialLoginResult.Failure(
                kind = FailureKind.CONFIGURATION,
                message = "Google $operation: serverClientId 가 비어 있다(설정 안 됨). Google 을 부르지 않았다",
                providerId = id,
            )
        }

    /** 보조 정리. 기기에 그 기능이 없을 수 있어 실패는 결과에 넣지 않는다. 코루틴 취소는 다시 던진다. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 주 정리가 이미 성공했다
        }
    }

    private suspend fun <T> call(operation: String, block: suspend () -> SocialLoginResult<T>): SocialLoginResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoogleErrors.toResult(e, operation)
        }

    internal companion object {
        const val VENDOR_HOST_BUSY: String = "HostBusy"
        const val VENDOR_HOST_NOT_STARTED: String = "HostNotStarted"
    }
}
