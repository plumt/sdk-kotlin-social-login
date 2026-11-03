package net.plumt.sociallogin.google.internal

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.GetCredentialRequest

/** 실제 Credential Manager 를 부르는 [GoogleGateway]. 단위 테스트 대상이 아니다(커버리지 제외 — build.gradle.kts). */
internal class DefaultGoogleGateway(private val appContext: Context) : GoogleGateway {

    override suspend fun getCredential(activity: Activity, option: CredentialOption): Credential {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        // 계정 선택 화면을 띄우므로 Activity Context 로 부른다
        return CredentialManager.create(activity).getCredential(activity, request).credential
    }

    override suspend fun clearCredentialState() {
        CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
    }
}
