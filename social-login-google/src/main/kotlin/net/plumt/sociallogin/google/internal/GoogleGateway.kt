package net.plumt.sociallogin.google.internal

import android.app.Activity
import androidx.credentials.Credential
import androidx.credentials.CredentialOption

/**
 * Credential Manager 호출의 얇은 경계. 실패는 Credential Manager 가 던진 예외 그대로다.
 * 분류는 [GoogleErrors], 결과 해석은 [GoogleCredentials] 가 한다.
 */
internal interface GoogleGateway {
    suspend fun getCredential(activity: Activity, option: CredentialOption): Credential

    suspend fun clearCredentialState()
}
