package net.plumt.sociallogin.kakao.internal

import android.app.Activity
import com.kakao.sdk.auth.model.OAuthToken

/**
 * 카카오 SDK 호출의 얇은 경계. 실패는 SDK 가 준 예외(ClientError·AuthError·ApiError·IOException 등)를 그대로 던진다.
 * 분류는 [KakaoErrors] 가 한다 — 그래서 분류·폴백 로직을 가짜 구현으로 검증할 수 있다.
 */
internal interface KakaoGateway {
    /** SDK 를 초기화한다. 이미 같은 키로 됐으면 아무 일도 하지 않는다. 다른 키로 됐으면 [IllegalStateException]. */
    fun initialize(nativeAppKey: String)

    fun isKakaoTalkLoginAvailable(activity: Activity): Boolean

    suspend fun loginWithKakaoTalk(activity: Activity, nonce: String?): OAuthToken

    suspend fun loginWithKakaoAccount(activity: Activity, nonce: String?): OAuthToken

    suspend fun logout()

    suspend fun unlink()

    suspend fun refreshToken(): OAuthToken
}
