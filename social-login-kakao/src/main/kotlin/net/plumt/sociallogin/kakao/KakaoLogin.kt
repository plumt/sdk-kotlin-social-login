package net.plumt.sociallogin.kakao

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginProvider
import net.plumt.sociallogin.kakao.internal.DefaultKakaoGateway
import net.plumt.sociallogin.kakao.internal.KakaoLoginProvider

/**
 * 카카오 로그인 Provider 를 만드는 진입점.
 *
 * ```kotlin
 * val kakao = KakaoLogin.provider(appContext, KakaoLoginConfig.builder(BuildConfig.KAKAO_NATIVE_APP_KEY).build())
 * val manager = SocialLoginManager.create(SocialLoginConfig.builder<Activity>().register(kakao).build())
 * ```
 *
 * - 카카오 SDK 초기화는 첫 호출 때 한 번, 프로세스 전역으로 한다. `Application.onCreate` 를 고칠 필요가 없다.
 *   한 프로세스에서 서로 다른 앱 키로 Provider 를 둘 만들면 나중 것의 호출은 [net.plumt.sociallogin.FailureKind.CONFIGURATION] 이다.
 * - 지원 동작: 로그인, 로그아웃, 연결 해제, 토큰 갱신. 토큰은 카카오 SDK 가 기기에 저장하고 관리한다.
 * - 로그인 결과에는 토큰만 담긴다. 이메일·프로필은 앱 서버가 토큰을 검증하며 사용자 정보 API 로 받는다.
 *   OIDC 를 켠 앱이면 ID 토큰도 온다.
 */
public object KakaoLogin {

    /** 이 Provider 의 수단 식별자. [ProviderId.KAKAO] 와 같다. */
    @JvmField
    public val PROVIDER_ID: ProviderId = ProviderId.KAKAO

    /**
     * 카카오 로그인 Provider 를 만든다. 가벼운 호출이고 SDK 를 아직 초기화하지 않는다.
     *
     * @param context 아무 Context. 내부에서는 Application Context 만 쥔다(Activity 누수 없음).
     */
    @JvmStatic
    public fun provider(context: Context, config: KakaoLoginConfig): SocialLoginProvider<Activity> =
        KakaoLoginProvider(config, DefaultKakaoGateway(context.applicationContext), Dispatchers.Main)
}
