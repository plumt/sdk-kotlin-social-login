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

    /**
     * 모든 모드가 연결 해제를 지원한다. 기본 모드는 Google Sign-In 의 `revokeAccess`, Credential Manager 모드는
     * `AuthorizationClient.revokeAccess`(0.1.0)로 철회한다.
     */
    override val capabilities: Set<Capability> = setOf(Capability.SIGN_OUT, Capability.UNLINK)

    /** 이 인스턴스에서 마지막으로 로그인한 계정. 메모리에만 둔다(프로세스가 끝나면 사라진다). 이메일은 로그·결과에 넣지 않는다. */
    private class SignedIn(val email: String?, val viaCredentialManager: Boolean)

    @Volatile
    private var lastSignIn: SignedIn? = null

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
        val viaCredentialManager = request.mode != GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN
        val result = withContext(mainDispatcher) {
            call("signIn") {
                if (viaCredentialManager) {
                    signInWithFallback(host, options.nonce, request)
                } else {
                    legacySignIn(host)
                }
            }
        }
        // 연결 해제가 어느 경로로, 어느 계정을 철회할지 기억한다
        if (result is SocialLoginResult.Success) lastSignIn = SignedIn(result.value.email, viaCredentialManager)
        return result
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
                cause = null,
            )
            GoogleSignInHostResult.HostGone -> SocialLoginResult.Cancelled
            GoogleSignInHostResult.NotStarted -> SocialLoginResult.Failure(
                kind = FailureKind.INTERRUPTED,
                message = "Google 계정 선택 화면을 띄울 호스트 Activity 가 " +
                    "${GoogleSignInHost.DEFAULT_START_TIMEOUT_MILLIS}ms 안에 뜨지 않았다 — 화면이 포그라운드일 때 다시 시도한다",
                providerId = id,
                vendorCode = VENDOR_HOST_NOT_STARTED,
                cause = null,
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
        lastSignIn = null
        SocialLoginResult.Success(Unit)
    }

    /** 계정을 이 인스턴스의 마지막 로그인에서 찾아 연결을 끊는다. 규칙은 [unlinkAccount]. */
    override suspend fun unlink(): SocialLoginResult<Unit> = unlinkAccount(explicitEmail = null)

    /** [accountEmail] 계정의 연결을 끊는다. 프로세스가 다시 떠 마지막 로그인을 모를 때 쓴다. 규칙은 [unlinkAccount]. */
    suspend fun unlink(accountEmail: String): SocialLoginResult<Unit> = unlinkAccount(explicitEmail = accountEmail)

    /**
     * Google 서버에서 이 앱에 준 권한을 철회하고 기기 로그인 상태를 지운다. 화면을 띄우지 않는다.
     *
     * 주 경로는 이 인스턴스의 마지막 로그인 경로, 모르면 설정 모드([GoogleLoginConfig.mode])다.
     * - Google Sign-In: `GoogleSignIn.revokeAccess`. 로그인한 계정이 없으면 `ApiException(4)` → [FailureKind.NOT_SIGNED_IN].
     * - Credential Manager: `AuthorizationClient.revokeAccess(계정)`. 계정은 [explicitEmail], 없으면 마지막 로그인의 이메일.
     *   둘 다 없으면 Google 을 부르지 않고 [FailureKind.NOT_SIGNED_IN] + [VENDOR_NO_ACCOUNT].
     *   그 계정의 동의 이력이 없어 철회할 권한이 없으면 성공이다(0.1.1, [revokeAuthorizationIfGranted]).
     *
     * 주 경로의 실패만 결과로 알린다. 다른 경로의 철회(계정을 알 때)와 Credential Manager 상태 지우기는 보조로 해 보고
     * 실패를 결과에 넣지 않는다. 성공하면 기억한 계정을 잊는다.
     */
    private suspend fun unlinkAccount(explicitEmail: String?): SocialLoginResult<Unit> {
        notConfigured("unlink")?.let { return it }
        val remembered = lastSignIn
        val email = explicitEmail ?: remembered?.email
        val viaCredentialManager = remembered?.viaCredentialManager ?: (config.mode != GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN)
        if (viaCredentialManager && email == null) {
            return SocialLoginResult.Failure(
                kind = FailureKind.NOT_SIGNED_IN,
                message = "Google unlink: 철회할 계정을 모른다 — 이 Provider 로 로그인하지 않았거나 프로세스가 다시 떴다. " +
                    "로그인 때 받은 이메일을 GoogleLogin.unlink(provider, accountEmail) 로 넘긴다. Google 을 부르지 않았다",
                providerId = id,
                vendorCode = VENDOR_NO_ACCOUNT,
                cause = null,
            )
        }
        return call("unlink") {
            if (viaCredentialManager) {
                revokeAuthorizationIfGranted(checkNotNull(email))
                bestEffort { gateway.legacyRevokeAccess(config.serverClientId) }
            } else {
                gateway.legacyRevokeAccess(config.serverClientId)
                if (email != null) bestEffort { gateway.revokeAuthorization(email) }
            }
            // 다음 로그인이 기본 계정을 조용히 고르지 않고 계정 선택·동의 화면을 다시 띄우게 한다
            bestEffort { gateway.clearCredentialState() }
            lastSignIn = null
            SocialLoginResult.Success(Unit)
        }
    }

    /**
     * Credential Manager 경로의 주 철회. 그 계정이 이 앱에 준 권한이 없으면(동의 이력 없음) 철회할 것이 없으므로 성공으로 보고
     * 이어서 기기 상태를 지운다. 판별은 [GoogleErrors.isNothingToRevoke] 하나뿐이고 그 밖의 실패는 그대로 던진다.
     */
    private suspend fun revokeAuthorizationIfGranted(email: String) {
        try {
            gateway.revokeAuthorization(email)
        } catch (e: Exception) {
            if (!GoogleErrors.isNothingToRevoke(e)) throw e
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

        /** Credential Manager 경로의 연결 해제에 철회할 계정이 없을 때의 vendorCode. */
        const val VENDOR_NO_ACCOUNT: String = "NoAccountToRevoke"
    }
}
