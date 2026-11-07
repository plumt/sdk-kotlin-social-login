package net.plumt.sociallogin.sample

import android.content.Context
import net.plumt.sociallogin.naver.NaverLogin
import net.plumt.sociallogin.naver.NaverLoginConfig

internal fun naver(context: Context) =
    NaverLogin.provider(context, NaverLoginConfig(clientId = "sample", clientSecret = "sample", clientName = "sample"))
