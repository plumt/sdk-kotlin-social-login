package net.plumt.sociallogin.naver.internal

import android.app.Activity
import com.navercorp.nid.oauth.NidOAuthErrorCode
import net.plumt.sociallogin.naver.NaverLoginConfig

/** SDK 가 기기에 저장해 둔 토큰. 없으면 각 값이 null(만료 시각은 0 이하). */
internal class NaverStoredToken(
    val accessToken: String?,
    val refreshToken: String?,
    /** Unix epoch 초. 네이버 SDK 단위다. */
    val expiresAtSeconds: Long,
)

/**
 * 네이버 SDK 의 실패 콜백을 담은 예외. 콜백 문구는 담지 않는다(서버 응답이 섞일 수 있다).
 *
 * @property code 실패 직후 SDK 가 알려 준 마지막 오류 코드.
 * @property httpStatus `onFailure` 의 HTTP 상태. `onError` 면 null.
 * @property callbackErrorCode `onError` 의 오류 번호. `onFailure` 면 null.
 */
internal class NaverSdkException(
    val code: NidOAuthErrorCode,
    val httpStatus: Int?,
    val callbackErrorCode: Int?,
) : Exception("네이버 SDK 실패: ${code.name} (HTTP $httpStatus, error $callbackErrorCode)")

/**
 * 네이버 SDK 호출의 얇은 경계. 콜백 실패는 [NaverSdkException], 그 밖은 SDK 가 던진 예외 그대로다.
 * 분류는 [NaverErrors] 가 한다.
 */
internal interface NaverGateway {
    /** SDK 를 초기화한다. 이미 같은 Client ID 로 됐으면 아무 일도 하지 않는다. 다른 ID 면 [IllegalStateException]. */
    fun initialize(config: NaverLoginConfig)

    suspend fun authenticate(activity: Activity)

    fun storedToken(): NaverStoredToken

    fun logout()

    suspend fun deleteToken()

    suspend fun refreshAccessToken()
}
