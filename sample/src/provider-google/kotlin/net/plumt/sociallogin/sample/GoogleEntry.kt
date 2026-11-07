package net.plumt.sociallogin.sample

import android.content.Context
import net.plumt.sociallogin.google.GoogleLogin
import net.plumt.sociallogin.google.GoogleLoginConfig

internal fun google(context: Context) =
    GoogleLogin.provider(context, GoogleLoginConfig(serverClientId = "sample.apps.googleusercontent.com"))
