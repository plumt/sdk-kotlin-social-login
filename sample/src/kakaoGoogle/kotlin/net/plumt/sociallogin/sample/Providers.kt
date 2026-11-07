package net.plumt.sociallogin.sample

import android.app.Activity
import android.content.Context
import net.plumt.sociallogin.SocialLoginProvider

internal fun providers(context: Context): List<SocialLoginProvider<Activity>> = listOf(kakao(context), google(context))
