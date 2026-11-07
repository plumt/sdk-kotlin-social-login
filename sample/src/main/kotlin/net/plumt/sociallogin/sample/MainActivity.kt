package net.plumt.sociallogin.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.plumt.sociallogin.SignInOptions
import net.plumt.sociallogin.SocialLoginManager
import net.plumt.sociallogin.SocialLoginResult

/**
 * 실행하지 않는 확인용 화면. 소비 앱처럼 공개 API 만 불러 벤더 SDK 경로(로그인·갱신·로그아웃·연결 해제)가 R8 에 살아남게 한다.
 * 키는 자리표시값이다.
 */
@OptIn(DelicateCoroutinesApi::class)
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val builder = SocialLoginManager.builder<Activity>()
        providers(applicationContext).forEach { builder.register(it) }
        val manager = builder.build()
        val first = FirstExtension(intent.getIntExtra("first", 1))
        val second = SecondExtension(intent.getIntExtra("second", 2))
        Log.i(TAG, "${first.next()} ${second.next()}")
        val options = SignInOptions("sample", listOf(first, second))
        GlobalScope.launch {
            for (id in manager.providerIds) {
                val result = manager.signIn(id, this@MainActivity, options)
                if (result is SocialLoginResult.Success) Log.i(TAG, result.value.toString())
                Log.i(TAG, "$result ${manager.signOut(id)} ${manager.unlink(id)} ${manager.refreshToken(id)}")
            }
        }
    }

    private companion object {
        const val TAG = "sample"
    }
}

/**
 * 모양이 같고 어디서도 타입으로 읽지 않는 Extension 두 개. R8 이 한 클래스로 합치면 [SignInOptions] 의 같은 클래스 검사에
 * 걸리므로, social-login-core 의 규칙이 둘을 갈라 두는지 verify 태스크가 mapping 으로 확인한다.
 */
class FirstExtension(private val value: Int) : SignInOptions.Extension {
    fun next(): Int = value + 1
}

class SecondExtension(private val value: Int) : SignInOptions.Extension {
    fun next(): Int = value + 2
}
