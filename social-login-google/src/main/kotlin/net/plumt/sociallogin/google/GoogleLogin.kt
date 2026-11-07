package net.plumt.sociallogin.google

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginProvider
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
 * - 지원 동작: 로그인, 로그아웃(기기의 로그인 상태 지우기). 기본 모드는 연결 해제(Google 서버의 권한 철회)도 한다.
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
}
