package net.plumt.sociallogin.naver.internal

import android.app.Activity
import android.content.Context
import com.navercorp.nid.NaverIdLoginSDK
import com.navercorp.nid.oauth.NidOAuthLogin
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.sociallogin.naver.NaverLoginConfig

/** 실제 네이버 SDK 를 부르는 [NaverGateway]. 단위 테스트 대상이 아니다(커버리지 제외 — build.gradle.kts). */
internal class DefaultNaverGateway(private val appContext: Context) : NaverGateway {

    override fun initialize(config: NaverLoginConfig) {
        SDK_INIT.ensure(config.clientId) {
            NaverIdLoginSDK.initialize(appContext, config.clientId, config.clientSecret, config.clientName)
        }
    }

    override suspend fun authenticate(activity: Activity): Unit = suspendCancellableCoroutine { cont ->
        NaverIdLoginSDK.authenticate(activity, naverCallback(cont) { NaverIdLoginSDK.getLastErrorCode() })
    }

    override fun storedToken(): NaverStoredToken = NaverStoredToken(
        accessToken = NaverIdLoginSDK.getAccessToken(),
        refreshToken = NaverIdLoginSDK.getRefreshToken(),
        expiresAtSeconds = NaverIdLoginSDK.getExpiresAt(),
    )

    override fun logout() {
        NaverIdLoginSDK.logout()
    }

    override suspend fun deleteToken(): Unit = suspendCancellableCoroutine { cont ->
        NidOAuthLogin().callDeleteTokenApi(naverCallback(cont) { NaverIdLoginSDK.getLastErrorCode() })
    }

    override suspend fun refreshAccessToken(): Unit = suspendCancellableCoroutine { cont ->
        NidOAuthLogin().callRefreshAccessTokenApi(naverCallback(cont) { NaverIdLoginSDK.getLastErrorCode() })
    }

    private companion object {
        /** 네이버 SDK 는 프로세스 전역이다. Provider 인스턴스가 여럿이어도 가드는 하나다. */
        val SDK_INIT = InitGuard("네이버 SDK")
    }
}
