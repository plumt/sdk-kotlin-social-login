package net.plumt.sociallogin.google.internal

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/** 호스트 Activity 로 띄운 계정 선택 화면의 결말. */
internal sealed interface GoogleSignInHostResult {
    /** 다른 요청의 호스트가 이미 떠 있다. */
    data object Busy : GoogleSignInHostResult

    /** 결과 없이 호스트가 사라졌다(사용자가 뒤로 감·시스템이 닫음). 사용자 취소로 본다. */
    data object HostGone : GoogleSignInHostResult

    /** 호스트 Activity 가 제한 시간 안에 뜨지 않았다. */
    data object NotStarted : GoogleSignInHostResult

    /** 호스트가 계정 선택 화면을 띄우지 못했다(예: `TransactionTooLargeException`·`ActivityNotFoundException`). */
    class Failed(val error: Throwable) : GoogleSignInHostResult

    /** 계정 선택 화면이 돌려준 결과. */
    class ActivityResult(val resultCode: Int, val data: Intent?) : GoogleSignInHostResult
}

/**
 * 계정 선택 인텐트를 투명 호스트 Activity 로 띄우고 결과를 코루틴으로 받는 다리. 프로세스 전역이고 요청은 한 번에 하나다.
 *
 * - 호출부 Activity 의 `onActivityResult` 를 빌리지 않아 앱이 결과 전달 코드를 쓰지 않는다.
 * - 회전으로 호스트가 다시 만들어지면 계정 선택 화면을 다시 띄우지 않는다. 결과는 새 인스턴스로 온다.
 * - 호스트가 결과 없이 사라지면 [GoogleSignInHostResult.HostGone], 요청 코루틴이 취소되면 호스트를 닫는다.
 * - 요청마다 번호를 붙여, 지난 요청의 늦게 뜬 호스트가 새 요청을 가로채지 않게 한다.
 */
internal object GoogleSignInHost {

    const val EXTRA_SIGN_IN_INTENT: String = "net.plumt.sociallogin.google.SIGN_IN_INTENT"
    const val EXTRA_REQUEST_ID: String = "net.plumt.sociallogin.google.REQUEST_ID"
    const val REQUEST_CODE: Int = 0x51A1

    /** 호스트 Activity 가 떠서 붙기까지 기다리는 시간. 계정 선택 화면 자체에는 시간 제한을 두지 않는다. */
    const val DEFAULT_START_TIMEOUT_MILLIS: Long = 10_000

    private const val NO_REQUEST = 0L

    private class Pending(val id: Long) {
        val attached = CompletableDeferred<Unit>()
        val result = CompletableDeferred<GoogleSignInHostResult>()
    }

    private var nextId = NO_REQUEST
    private var pending: Pending? = null
    private var host: WeakReference<Activity>? = null

    /**
     * [signInIntent] 를 호스트 Activity 로 띄우고 결말을 기다린다. 메인 스레드에서 부른다.
     *
     * @throws RuntimeException 호스트 Activity 를 시작하지 못했을 때(`startActivity` 가 던진 그대로).
     */
    suspend fun launch(
        caller: Activity,
        signInIntent: Intent,
        startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    ): GoogleSignInHostResult {
        val request = synchronized(this) {
            if (pending != null) return GoogleSignInHostResult.Busy
            nextId++
            if (nextId == NO_REQUEST) nextId++
            Pending(nextId).also { pending = it }
        }
        try {
            caller.startActivity(
                Intent(caller, GoogleSignInHostActivity::class.java)
                    .putExtra(EXTRA_SIGN_IN_INTENT, signInIntent)
                    .putExtra(EXTRA_REQUEST_ID, request.id),
            )
            if (withTimeoutOrNull(startTimeoutMillis) { request.attached.await() } == null) {
                return GoogleSignInHostResult.NotStarted
            }
            return request.result.await()
        } finally {
            // 정상 결말이면 이미 비어 있다. 시간 초과·코루틴 취소·예외면 여기서 요청을 지우고 호스트를 닫는다
            abandon(request)
        }
    }

    /** 호스트가 떴다. 기다리는 같은 번호의 요청이 없으면 false — 호스트는 바로 닫는다. */
    fun attach(requestId: Long, activity: Activity): Boolean = synchronized(this) {
        val request = pending
        if (request == null || request.id != requestId) return false
        host = WeakReference(activity)
        request.attached.complete(Unit)
        true
    }

    /** 결말을 한 번만 전달한다. 같은 번호의 요청이 없으면(이미 끝남) 무시한다. */
    fun complete(requestId: Long, result: GoogleSignInHostResult) {
        synchronized(this) {
            val request = pending ?: return
            if (request.id != requestId) return
            pending = null
            host = null
            request.result.complete(result)
        }
    }

    /** 구성 변경이 아닌 이유로 호스트가 사라졌다. 그 호스트가 붙어 있던 요청이면 [GoogleSignInHostResult.HostGone]. */
    fun hostGone(requestId: Long, activity: Activity) {
        synchronized(this) {
            if (host?.get() !== activity) return
        }
        complete(requestId, GoogleSignInHostResult.HostGone)
    }

    /** 테스트·비상용 — 남은 요청과 호스트를 모두 정리한다. */
    fun reset() {
        val request = synchronized(this) { pending.also { pending = null } }
        request?.result?.complete(GoogleSignInHostResult.HostGone)
        finishHost()
    }

    private fun abandon(request: Pending) {
        val wasPending = synchronized(this) {
            if (pending === request) {
                pending = null
                true
            } else {
                false
            }
        }
        if (wasPending) finishHost()
    }

    private fun finishHost() {
        val activity = synchronized(this) { host?.get().also { host = null } }
        activity?.finish()
    }

    @Suppress("DEPRECATION")
    fun signInIntentOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SIGN_IN_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_SIGN_IN_INTENT)
        }

    fun requestIdOf(intent: Intent): Long = intent.getLongExtra(EXTRA_REQUEST_ID, NO_REQUEST)
}

/**
 * 계정 선택 인텐트를 띄우고 결과를 [GoogleSignInHost] 로 넘기는 투명 Activity. 라이브러리 매니페스트가 선언한다.
 * 앱이 직접 띄우지 않는다.
 */
internal class GoogleSignInHostActivity : Activity() {

    private var requestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = GoogleSignInHost.requestIdOf(intent)
        // 기다리는 요청이 없다 — 프로세스가 다시 떴거나 요청이 이미 끝났다
        if (!GoogleSignInHost.attach(requestId, this)) {
            finish()
            return
        }
        // 회전 등으로 다시 만들어질 때는 띄우지 않는다. 처음 띄운 화면의 결과가 새 인스턴스의 onActivityResult 로 온다.
        // 여기서 또 띄우면 계정 선택 화면이 겹치고 두 번째 화면이 결과 처리 뒤에도 남는다
        if (savedInstanceState != null) return
        val signInIntent = GoogleSignInHost.signInIntentOf(intent)
        if (signInIntent == null) {
            GoogleSignInHost.complete(
                requestId,
                GoogleSignInHostResult.Failed(IllegalStateException("호스트 Activity 에 계정 선택 인텐트가 없다")),
            )
            finish()
            return
        }
        try {
            startActivityForResult(signInIntent, GoogleSignInHost.REQUEST_CODE)
        } catch (e: RuntimeException) {
            // TransactionTooLargeException(RuntimeException 으로 감싸져 온다)·ActivityNotFoundException 등. 앱을 죽이지 않는다
            GoogleSignInHost.complete(requestId, GoogleSignInHostResult.Failed(e))
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GoogleSignInHost.REQUEST_CODE) return
        GoogleSignInHost.complete(requestId, GoogleSignInHostResult.ActivityResult(resultCode, data))
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) GoogleSignInHost.hostGone(requestId, this)
        super.onDestroy()
    }
}
