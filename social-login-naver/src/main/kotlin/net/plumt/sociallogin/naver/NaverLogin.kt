package net.plumt.sociallogin.naver

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import net.plumt.sociallogin.ProviderId
import net.plumt.sociallogin.SocialLoginProvider
import net.plumt.sociallogin.naver.internal.DefaultNaverGateway
import net.plumt.sociallogin.naver.internal.NaverLoginProvider

/**
 * 네이버 로그인 Provider 를 만드는 진입점.
 *
 * ```kotlin
 * val naver = NaverLogin.provider(
 *     appContext,
 *     NaverLoginConfig(
 *         clientId = BuildConfig.NAVER_CLIENT_ID,
 *         clientSecret = BuildConfig.NAVER_CLIENT_SECRET,
 *         clientName = getString(R.string.app_name),
 *     ),
 * )
 * ```
 *
 * - 네이버 SDK 초기화는 첫 호출 때 한 번, 프로세스 전역으로 한다. `Application.onCreate` 를 고칠 필요가 없다.
 *   한 프로세스에서 서로 다른 Client ID 로 Provider 를 둘 만들면 나중 것의 호출은 [net.plumt.sociallogin.FailureKind.CONFIGURATION] 이다.
 * - 지원 동작: 로그인, 로그아웃(기기의 토큰만 지운다), 연결 해제(네이버 서버의 토큰 삭제), 토큰 갱신.
 * - 네이버는 ID 토큰이 없다. 로그인 결과에는 액세스·갱신 토큰만 담기고, nonce 를 넘기면
 *   [net.plumt.sociallogin.FailureKind.UNSUPPORTED] 로 끝난다.
 */
public object NaverLogin {

    /** 이 Provider 의 수단 식별자. [ProviderId.NAVER] 와 같다. */
    @JvmField
    public val PROVIDER_ID: ProviderId = ProviderId.NAVER

    /**
     * 네이버 로그인 Provider 를 만든다. 가벼운 호출이고 SDK 를 아직 초기화하지 않는다.
     *
     * @param context 아무 Context. 내부에서는 Application Context 만 쥔다(Activity 누수 없음).
     */
    @JvmStatic
    public fun provider(context: Context, config: NaverLoginConfig): SocialLoginProvider<Activity> =
        NaverLoginProvider(config, DefaultNaverGateway(context.applicationContext), Dispatchers.Main)
}
