package net.plumt.sociallogin.google.internal

import android.app.Activity
import android.content.Intent
import androidx.credentials.Credential
import androidx.credentials.CredentialOption

/** Google Sign-In 계정 선택 결과에서 옮길 값. 로그인 응답에 든 것만 담는다. */
internal class LegacyGoogleAccount(
    val idToken: String?,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
)

/**
 * Google 호출의 얇은 경계. 실패는 Credential Manager·Google Play 서비스가 던진 예외 그대로다
 * (`GetCredentialException`·`ClearCredentialException`·`ApiException` 등).
 * 분류는 [GoogleErrors], 결과 해석은 [GoogleCredentials]·[GoogleLegacy] 가 한다.
 */
internal interface GoogleGateway {
    suspend fun getCredential(activity: Activity, option: CredentialOption): Credential

    suspend fun clearCredentialState()

    /** Google Sign-In 계정 선택 인텐트. ID 토큰(`aud` = [serverClientId])과 이메일을 요청한다. */
    fun legacySignInIntent(activity: Activity, serverClientId: String): Intent

    /** [intent] 를 투명 호스트 Activity 로 띄우고 결말을 기다린다. */
    suspend fun launchHost(activity: Activity, intent: Intent): GoogleSignInHostResult

    /** 계정 선택 결과 인텐트를 해석한다. 실패는 `ApiException`. */
    fun legacyAccountFrom(data: Intent): LegacyGoogleAccount

    /** Google Sign-In 의 기기 로그인 상태를 지운다. */
    suspend fun legacySignOut(serverClientId: String)

    /** 이 앱에 준 권한을 Google 서버에서 철회하고 기기 로그인 상태도 지운다. 로그인한 계정이 없으면 `ApiException(4)`. */
    suspend fun legacyRevokeAccess(serverClientId: String)

    /**
     * Credential Manager 로그인의 권한 철회. `AuthorizationClient.revokeAccess` 로 [accountEmail] 계정이 이 앱에 준
     * 권한을 모두 철회하고 그 계정의 캐시 토큰을 지운다. 화면을 띄우지 않는다. 실패는 `ApiException` 등 그대로.
     */
    suspend fun revokeAuthorization(accountEmail: String)
}
