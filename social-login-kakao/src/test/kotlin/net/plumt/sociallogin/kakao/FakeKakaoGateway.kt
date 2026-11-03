package net.plumt.sociallogin.kakao

import android.app.Activity
import com.kakao.sdk.auth.model.OAuthToken
import kotlinx.coroutines.currentCoroutineContext
import net.plumt.sociallogin.kakao.internal.KakaoGateway
import java.util.Date
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** 테스트용 토큰. 값은 짧은 가짜다. */
internal fun oauthToken(
    access: String = "at",
    refresh: String = "rt",
    idToken: String? = null,
    accessExp: Long = 1_000,
    refreshExp: Long = 2_000,
    scopes: List<String>? = null,
) = OAuthToken(access, Date(accessExp), refresh, Date(refreshExp), idToken, scopes)

/** 기기에 저장된 토큰 흉내 — 프로세스가 다시 떠도 남는 저장소. */
internal class FakeTokenStore {
    var token: OAuthToken? = null
}

internal class FakeKakaoGateway(private val store: FakeTokenStore = FakeTokenStore()) : KakaoGateway {
    val calls = mutableListOf<String>()
    val nonces = mutableListOf<String?>()
    var interceptor: CoroutineContext.Element? = null

    var initError: Exception? = null
    var talkAvailable: () -> Boolean = { true }
    var talk: suspend () -> OAuthToken = { oauthToken() }
    var web: suspend () -> OAuthToken = { oauthToken(access = "web") }
    var logout: suspend () -> Unit = { store.token = null }
    var unlink: suspend () -> Unit = { store.token = null }
    var refresh: suspend () -> OAuthToken = {
        val old = store.token ?: throw com.kakao.sdk.common.model.ClientError(
            com.kakao.sdk.common.model.ClientErrorCause.TokenNotFound, "no token",
        )
        oauthToken(access = old.accessToken + "2", refresh = old.refreshToken).also { store.token = it }
    }

    override fun initialize(nativeAppKey: String) {
        calls += "init:$nativeAppKey"
        initError?.let { throw it }
    }

    override fun isKakaoTalkLoginAvailable(activity: Activity): Boolean {
        calls += "available"
        return talkAvailable()
    }

    override suspend fun loginWithKakaoTalk(activity: Activity, nonce: String?): OAuthToken {
        calls += "talk"
        nonces += nonce
        interceptor = currentCoroutineContext()[ContinuationInterceptor]
        return talk().also { store.token = it }
    }

    override suspend fun loginWithKakaoAccount(activity: Activity, nonce: String?): OAuthToken {
        calls += "web"
        nonces += nonce
        interceptor = currentCoroutineContext()[ContinuationInterceptor]
        return web().also { store.token = it }
    }

    override suspend fun logout() {
        calls += "logout"
        logout.invoke()
    }

    override suspend fun unlink() {
        calls += "unlink"
        unlink.invoke()
    }

    override suspend fun refreshToken(): OAuthToken {
        calls += "refresh"
        return refresh.invoke()
    }
}
