package net.plumt.sociallogin.google.internal

import androidx.credentials.Credential
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import net.plumt.sociallogin.FailureKind
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialAccount
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.SocialToken
import net.plumt.sociallogin.google.GoogleLoginConfig
import net.plumt.sociallogin.google.GoogleSignInExtension
import net.plumt.sociallogin.google.GoogleSignInMode

/** 로그인 한 번의 화면 형태. 설정 값을 [GoogleSignInExtension] 이 있으면 그것으로 덮는다. */
internal class GoogleRequest(
    val mode: GoogleSignInMode,
    val filterByAuthorizedAccounts: Boolean,
    val autoSelectEnabled: Boolean,
) {
    fun withMode(mode: GoogleSignInMode): GoogleRequest =
        if (mode == GoogleSignInMode.BOTTOM_SHEET) GoogleRequest(mode, filterByAuthorizedAccounts, autoSelectEnabled) else GoogleRequest(mode, false, false)

    companion object {
        fun of(config: GoogleLoginConfig, extension: GoogleSignInExtension?): GoogleRequest =
            if (extension != null) {
                GoogleRequest(extension.mode, extension.filterByAuthorizedAccounts, extension.autoSelectEnabled)
            } else {
                GoogleRequest(config.mode, config.filterByAuthorizedAccounts, config.autoSelectEnabled)
            }
    }
}

/** 설정과 nonce 로 Credential Manager 요청 옵션을 만든다. */
internal object GoogleOptions {
    fun build(config: GoogleLoginConfig, nonce: String?, mode: GoogleSignInMode = config.mode): CredentialOption =
        build(config.serverClientId, GoogleRequest.of(config, null).withMode(mode), nonce)

    /** @throws IllegalArgumentException [GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN] 처럼 Credential Manager 옵션이 없는 모드일 때. */
    fun build(serverClientId: String, request: GoogleRequest, nonce: String?): CredentialOption = when (request.mode) {
        GoogleSignInMode.BOTTOM_SHEET -> GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(request.filterByAuthorizedAccounts)
            .setAutoSelectEnabled(request.autoSelectEnabled)
            .apply { if (nonce != null) setNonce(nonce) }
            .build()
        GoogleSignInMode.BUTTON -> GetSignInWithGoogleOption.Builder(serverClientId)
            .apply { if (nonce != null) setNonce(nonce) }
            .build()
        GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN ->
            throw IllegalArgumentException("LEGACY_GOOGLE_SIGN_IN 은 Credential Manager 옵션이 없다")
    }
}

/** Google Sign-In 계정 선택 결과를 해석한다. 로그인 응답에 든 것만 옮긴다. */
internal object GoogleLegacy {

    private val ID = ProviderId.GOOGLE

    /** @return 성공이면 계정. ID 토큰이 비면 [FailureKind.UNKNOWN](웹 클라이언트 ID 를 확인하라는 안내 포함). */
    fun toResult(account: LegacyGoogleAccount): SocialLoginResult<SocialAccount> {
        val idToken = account.idToken?.takeIf { it.isNotBlank() }
            ?: return SocialLoginResult.Failure(
                kind = FailureKind.UNKNOWN,
                message = "Google Sign-In 결과에 ID 토큰이 없다 — serverClientId 가 웹 애플리케이션 클라이언트 ID 인지 확인한다",
                providerId = ID,
            )
        return SocialLoginResult.Success(
            SocialAccount(
                providerId = ID,
                token = SocialToken(accessToken = null, idToken = idToken),
                email = account.email?.takeIf { it.isNotBlank() },
                displayName = account.displayName?.takeIf { it.isNotBlank() },
                profileImageUrl = account.photoUrl?.takeIf { it.isNotBlank() },
            ),
        )
    }
}

/** Credential Manager 가 돌려준 자격 증명을 해석한다. 로그인 응답에 든 것만 옮긴다. */
internal object GoogleCredentials {

    private val ID = ProviderId.GOOGLE

    /** Google ID 토큰 자격 증명 타입. 시트와 버튼 흐름이 다른 타입 문자열을 쓸 수 있다. */
    private val GOOGLE_TYPES = setOf(
        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
    )

    /**
     * @return 성공이면 계정. Google 자격 증명이 아니거나 ID 토큰이 비면 [FailureKind.UNKNOWN].
     * @throws com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException 데이터가 깨졌을 때
     *   (호출부가 [GoogleErrors] 로 분류한다).
     */
    fun toResult(credential: Credential): SocialLoginResult<SocialAccount> {
        if (credential !is CustomCredential || credential.type !in GOOGLE_TYPES) {
            return SocialLoginResult.Failure(
                kind = FailureKind.UNKNOWN,
                message = "Google ID 토큰이 아닌 자격 증명을 받았다: ${credential.type}",
                providerId = ID,
                vendorCode = credential.type,
            )
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        if (google.idToken.isBlank()) {
            return SocialLoginResult.Failure(
                kind = FailureKind.UNKNOWN,
                message = "Google 자격 증명의 ID 토큰이 비어 있다",
                providerId = ID,
            )
        }
        return SocialLoginResult.Success(
            SocialAccount(
                providerId = ID,
                token = SocialToken(accessToken = null, idToken = google.idToken),
                // Google 자격 증명의 id 는 계정 이메일이다
                email = google.id.takeIf { it.isNotBlank() },
                displayName = google.displayName?.takeIf { it.isNotBlank() },
                profileImageUrl = google.profilePictureUri?.toString()?.takeIf { it.isNotBlank() },
            ),
        )
    }
}
