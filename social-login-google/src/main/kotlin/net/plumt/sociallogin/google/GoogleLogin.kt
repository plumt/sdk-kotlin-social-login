package net.plumt.sociallogin.google

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginProvider
import net.plumt.sociallogin.google.internal.DefaultGoogleGateway
import net.plumt.sociallogin.google.internal.GoogleLoginProvider

/**
 * Google 로그인 Provider 를 만드는 진입점. Credential Manager 와 Sign in with Google(googleid)을 쓴다.
 * 지원이 끝난 `GoogleSignIn`(play-services-auth) API 는 쓰지 않는다.
 *
 * ```kotlin
 * val google = GoogleLogin.provider(appContext, GoogleLoginConfig(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID))
 * manager.signIn(ProviderId.GOOGLE, activity, SignInOptions(nonce = nonceFromServer))
 * ```
 *
 * - 결과는 Google **ID 토큰**이다(액세스 토큰 없음). 서버가 서명·`aud`(= serverClientId)·`exp`·`nonce` 를 검증한다.
 * - 계정이 많은 기기에서 계정 선택 시트가 `TransactionTooLargeException` 으로 실패하면 버튼 흐름으로 한 번 다시 시도한다.
 *   그것도 실패하면 [net.plumt.sociallogin.FailureKind.UNSUPPORTED] + vendorCode `TransactionTooLargeException` 이다.
 * - 지원 동작: 로그인, 로그아웃(Credential Manager 상태 지우기 — 다음 로그인에서 자동 선택이 풀린다).
 * - 연결 해제(권한 철회)와 토큰 갱신은 지원하지 않는다([net.plumt.sociallogin.FailureKind.UNSUPPORTED]).
 *   철회는 서버가 Google OAuth 철회 엔드포인트로 하고, 새 ID 토큰은 다시 로그인([GoogleSignInMode.BOTTOM_SHEET] +
 *   `filterByAuthorizedAccounts`·`autoSelectEnabled`)으로 받는다.
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
}
