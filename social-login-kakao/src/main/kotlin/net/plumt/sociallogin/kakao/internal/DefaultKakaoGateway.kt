package net.plumt.sociallogin.kakao.internal

import android.app.Activity
import android.content.Context
import com.kakao.sdk.auth.AuthApiClient
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.user.UserApiClient
import kotlinx.coroutines.suspendCancellableCoroutine

/** 실제 카카오 SDK 를 부르는 [KakaoGateway]. 단위 테스트 대상이 아니다(커버리지 제외 — build.gradle.kts). */
internal class DefaultKakaoGateway(private val appContext: Context) : KakaoGateway {

    override fun initialize(nativeAppKey: String) {
        SDK_INIT.ensure(nativeAppKey) { KakaoSdk.init(appContext, nativeAppKey) }
    }

    override fun isKakaoTalkLoginAvailable(activity: Activity): Boolean =
        UserApiClient.instance.isKakaoTalkLoginAvailable(activity)

    override suspend fun loginWithKakaoTalk(activity: Activity, nonce: String?): OAuthToken =
        suspendCancellableCoroutine { cont ->
            UserApiClient.instance.loginWithKakaoTalk(activity, nonce = nonce) { token, error ->
                cont.resumeIfActive(tokenCallbackResult(token, error))
            }
        }

    override suspend fun loginWithKakaoAccount(activity: Activity, nonce: String?): OAuthToken =
        suspendCancellableCoroutine { cont ->
            UserApiClient.instance.loginWithKakaoAccount(activity, nonce = nonce) { token, error ->
                cont.resumeIfActive(tokenCallbackResult(token, error))
            }
        }

    override suspend fun logout(): Unit = suspendCancellableCoroutine { cont ->
        UserApiClient.instance.logout { error -> cont.resumeIfActive(unitCallbackResult(error)) }
    }

    override suspend fun unlink(): Unit = suspendCancellableCoroutine { cont ->
        UserApiClient.instance.unlink { error -> cont.resumeIfActive(unitCallbackResult(error)) }
    }

    override suspend fun refreshToken(): OAuthToken = suspendCancellableCoroutine { cont ->
        AuthApiClient.instance.refreshToken { token, error -> cont.resumeIfActive(tokenCallbackResult(token, error)) }
    }

    private companion object {
        /** 카카오 SDK 는 프로세스 전역이다. Provider 인스턴스가 여럿이어도 가드는 하나다. */
        val SDK_INIT = InitGuard("카카오 SDK")
    }
}
