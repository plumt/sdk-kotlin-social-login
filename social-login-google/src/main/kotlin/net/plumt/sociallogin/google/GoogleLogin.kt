package net.plumt.sociallogin.google

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginProvider
import net.plumt.sociallogin.SocialLoginResult
import net.plumt.sociallogin.google.internal.DefaultGoogleGateway
import net.plumt.sociallogin.google.internal.GoogleLoginProvider

/**
 * Google 로그인 Provider 를 만드는 진입점.
 *
 * ```kotlin
 * val google = GoogleLogin.provider(appContext, GoogleLoginConfig(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID))
 * manager.signIn(ProviderId.GOOGLE, activity)
 * ```
 *
 * - 결과는 Google **ID 토큰**이다(액세스 토큰 없음). 서버가 서명·`aud`(= serverClientId)·`exp`(·`nonce`)를 검증한다.
 * - 기본 모드 [GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN] 은 Google Sign-In 계정 선택 화면을 투명 호스트 Activity 로 띄운다.
 *   계정이 많은 고밀도 기기에서 Credential Manager 시트가 Binder 전송 한도에 걸려 뜨지 못한 사례를 피하는 경로다.
 *   지원 중단 표시가 붙은 API 이고 nonce 를 넣을 수 없다. nonce 가 필요하면 [GoogleSignInMode.BUTTON]·
 *   [GoogleSignInMode.BOTTOM_SHEET](Credential Manager)를 고른다.
 * - Credential Manager 모드에서 계정 선택 시트가 `TransactionTooLargeException` 으로 실패하면 버튼 흐름으로 한 번 다시
 *   시도한다. 어느 모드든 그 예외로 끝나면 [net.plumt.sociallogin.FailureKind.UNSUPPORTED] + vendorCode
 *   `TransactionTooLargeException` 이다.
 * - 지원 동작: 로그인, 로그아웃(기기의 로그인 상태 지우기), 연결 해제(Google 서버의 권한 철회). 연결 해제는 기본 모드가
 *   Google Sign-In `revokeAccess`, Credential Manager 모드가 `AuthorizationClient.revokeAccess`(0.1.0)로 한다.
 *   토큰 갱신은 지원하지 않는다 — 새 ID 토큰은 다시 로그인해 받는다.
 */
public object GoogleLogin {

    /** 이 Provider 의 수단 식별자. [ProviderId.GOOGLE] 과 같다. */
    @JvmField
    public val PROVIDER_ID: ProviderId = ProviderId.GOOGLE

    /**
     * Google 로그인 Provider 를 만든다. 가벼운 호출이다.
     *
     * @param context 아무 Context. 내부에서는 Application Context 만 쥔다(Activity 누수 없음).
     */
    @JvmStatic
    public fun provider(context: Context, config: GoogleLoginConfig): SocialLoginProvider<Activity> =
        GoogleLoginProvider(config, DefaultGoogleGateway(context.applicationContext), Dispatchers.Main)

    /**
     * [accountEmail] 계정의 Google 연결을 끊는다(회원 탈퇴 때, 0.1.0). Google 서버에서 이 앱에 준 권한을 철회하고 기기
     * 로그인 상태를 지운다. 화면을 띄우지 않는다. Credential Manager 모드에서 그 계정이 이 앱에 권한을 준 적이 없으면
     * (동의 이력 없음) 철회할 것이 없으므로 기기 상태만 지우고 [net.plumt.sociallogin.SocialLoginResult.Success] 다(0.1.1).
     *
     * `SocialLoginManager.unlink(ProviderId.GOOGLE)`(= 인자 없는 `unlink()`)는 그 Provider 로 **이 프로세스에서** 로그인한
     * 계정만 안다. Credential Manager 모드(`BUTTON`·`BOTTOM_SHEET`)에서 앱이 다시 떠 로그인 없이 탈퇴하면 철회할 계정을
     * 몰라 [net.plumt.sociallogin.FailureKind.NOT_SIGNED_IN] 이다. 그때 로그인 결과의
     * [net.plumt.sociallogin.SocialAccount.email] 을 앱이 보관했다가 여기로 넘긴다.
     *
     * ```kotlin
     * when (val r = GoogleLogin.unlink(googleProvider, accountEmail = savedEmail)) {
     *     is SocialLoginResult.Success -> api.deleteAccount()
     *     SocialLoginResult.Cancelled -> Unit
     *     is SocialLoginResult.Failure -> if (r.kind.retryable) showRetry() else showError(r.kind)
     * }
     * ```
     *
     * - `SocialLoginManager` 의 동시 호출 잠금을 거치지 않는다. 같은 Provider 의 다른 호출과 겹치지 않게 부른다.
     * - 결과는 인자 없는 `unlink()` 와 같다. 오류 표는 ERRORS.md 를 본다. 계정 이메일은 결과·로그에 넣지 않는다.
     *
     * @param provider [provider] 로 만든 Google Provider.
     * @param accountEmail 로그인 때 받은 Google 계정 이메일. 앞뒤 공백은 지운다.
     * @throws IllegalArgumentException [provider] 가 [provider] 로 만든 것이 아니거나 [accountEmail] 이 공백뿐일 때.
     */
    @JvmStatic
    public suspend fun unlink(provider: SocialLoginProvider<Activity>, accountEmail: String): SocialLoginResult<Unit> {
        require(provider is GoogleLoginProvider) {
            "provider 는 GoogleLogin.provider 로 만든 것이어야 한다: 받은 타입 ${provider.javaClass.name}"
        }
        require(accountEmail.isNotBlank()) { "accountEmail 이 공백뿐이다(길이 ${accountEmail.length})" }
        return provider.unlink(accountEmail.trim())
    }
}
