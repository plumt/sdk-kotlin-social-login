package net.plumt.sociallogin.sample

import android.content.Context
import net.plumt.sociallogin.kakao.KakaoLogin
import net.plumt.sociallogin.kakao.KakaoLoginConfig

internal fun kakao(context: Context) =
    KakaoLogin.provider(context, KakaoLoginConfig.builder("00000000000000000000000000000000").build())
