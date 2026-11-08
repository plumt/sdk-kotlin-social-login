package net.plumt.sociallogin.google.internal

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 실제 Credential Manager·Google Sign-In·AuthorizationClient 를 부르는 [GoogleGateway].
 * 단위 테스트 대상이 아니다(커버리지 제외 — build.gradle.kts).
 */
// Google Sign-In(play-services-auth)은 지원 중단 표시가 붙었지만, 계정이 많은 기기에서도 뜨는 경로라 기본 모드로 쓴다
@Suppress("DEPRECATION")
internal class DefaultGoogleGateway(private val appContext: Context) : GoogleGateway {

    override suspend fun getCredential(activity: Activity, option: CredentialOption): Credential {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        // 계정 선택 화면을 띄우므로 Activity Context 로 부른다
        return CredentialManager.create(activity).getCredential(activity, request).credential
    }

    override suspend fun clearCredentialState() {
        CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
    }

    override fun legacySignInIntent(activity: Activity, serverClientId: String): Intent =
        GoogleSignIn.getClient(activity, options(serverClientId)).signInIntent

    override suspend fun launchHost(activity: Activity, intent: Intent): GoogleSignInHostResult =
        GoogleSignInHost.launch(activity, intent)

    override fun legacyAccountFrom(data: Intent): LegacyGoogleAccount {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        return LegacyGoogleAccount(
            idToken = account.idToken,
            email = account.email,
            displayName = account.displayName,
            photoUrl = account.photoUrl?.toString(),
        )
    }

    override suspend fun legacySignOut(serverClientId: String) {
        GoogleSignIn.getClient(appContext, options(serverClientId)).signOut().await()
    }

    override suspend fun legacyRevokeAccess(serverClientId: String) {
        GoogleSignIn.getClient(appContext, options(serverClientId)).revokeAccess().await()
    }

    override suspend fun revokeAuthorization(accountEmail: String) {
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(accountEmail, GOOGLE_ACCOUNT_TYPE))
            // 철회는 요청한 scope 와 무관하게 이 앱에 준 권한 전부에 미친다. 로그인이 받는 기본 scope 를 적는다
            .setScopes(listOf(Scope(Scopes.OPEN_ID), Scope(Scopes.EMAIL), Scope(Scopes.PROFILE)))
            .build()
        Identity.getAuthorizationClient(appContext).revokeAccess(request).await()
    }

    private fun options(serverClientId: String): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(serverClientId)
            .requestEmail()
            .build()

    private suspend fun Task<Void>.await(): Unit = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (!cont.isActive) return@addOnCompleteListener
            val error = task.exception
            when {
                task.isSuccessful -> cont.resume(Unit)
                error != null -> cont.resumeWithException(error)
                else -> cont.resumeWithException(IllegalStateException("Google Play 서비스 작업이 원인 없이 실패했다(취소됨)"))
            }
        }
    }

    private companion object {
        /** 기기 Google 계정의 `android.accounts.Account` 타입. */
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
