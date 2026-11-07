package net.plumt.sociallogin.google

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.sociallogin.google.internal.GoogleSignInHost
import net.plumt.sociallogin.google.internal.GoogleSignInHostActivity
import net.plumt.sociallogin.google.internal.GoogleSignInHostResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * 투명 호스트 Activity 를 가짜 없이 Robolectric 으로 띄워 검증한다. Robolectric 은 모든 startActivity·
 * startActivityForResult 를 전역 목록에 남기므로, 계정 선택 인텐트가 몇 번 시작됐는지를 그 목록에서 센다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 35])
class GoogleSignInHostTest {

    private val signInIntent = Intent(SIGN_IN_ACTION)
    private lateinit var caller: Activity

    @Before
    fun setUp() {
        GoogleSignInHost.reset()
        caller = Robolectric.buildActivity(Activity::class.java).setup().get()
        drainStarts()
    }

    @After
    fun tearDown() {
        // 다리는 프로세스 전역 object 라 테스트 사이에 대기 요청이 남지 않게 비운다
        GoogleSignInHost.reset()
    }

    /** 호출자가 띄운 호스트 시작 인텐트로 호스트를 만든다(EXTRA 포함 실제 경로). */
    private fun startHost(): ActivityController<GoogleSignInHostActivity> {
        val hostStart = drainStarts().single { it.component?.className == GoogleSignInHostActivity::class.java.name }
        return Robolectric.buildActivity(GoogleSignInHostActivity::class.java, hostStart).setup()
    }

    @Test
    fun `결과가 호출자에게 한 번만 전달되고 호스트는 스스로 닫힌다`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val host = startHost()
        runCurrent()
        assertEquals(1, drainSignInStarts())

        val data = Intent().putExtra("account", "a@b.c")
        shadowOf(host.get()).receiveResult(signInIntent, Activity.RESULT_OK, data)
        runCurrent()

        val result = pending.await() as GoogleSignInHostResult.ActivityResult
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals("a@b.c", result.data?.getStringExtra("account"))
        assertTrue(host.get().isFinishing)

        // 같은 결과가 한 번 더 와도(중복 콜백) 이미 끝난 요청에는 영향이 없다
        GoogleSignInHost.complete(GoogleSignInHost.requestIdOf(host.get().intent), GoogleSignInHostResult.HostGone)
        assertTrue(pending.await() is GoogleSignInHostResult.ActivityResult)
    }

    @Test
    fun `회전으로 다시 만들어져도 계정 선택 화면은 한 번만 뜨고 결과는 새 인스턴스로 온다`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val host = startHost()
        assertEquals(1, drainSignInStarts())

        host.recreate()
        runCurrent()
        assertEquals("재생성 때 다시 띄우면 계정 선택 화면이 겹친다", 0, drainSignInStarts())
        assertFalse("구성 변경은 취소가 아니다", pending.isCompleted)

        shadowOf(host.get()).receiveResult(signInIntent, Activity.RESULT_CANCELED, null)
        runCurrent()
        val result = pending.await() as GoogleSignInHostResult.ActivityResult
        assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        assertNull(result.data)
    }

    @Test
    fun `결과 없이 호스트가 닫히면 HostGone`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val host = startHost()
        host.pause().stop().destroy()
        runCurrent()
        assertSame(GoogleSignInHostResult.HostGone, pending.await())
    }

    @Test
    fun `진행 중이면 두 번째 요청은 Busy 이고 호스트를 띄우지 않는다`() = runTest(StandardTestDispatcher()) {
        val first = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val second = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        assertSame(GoogleSignInHostResult.Busy, second.await())
        startHost() // 시작 인텐트가 정확히 하나다(single)
        first.cancel()
    }

    @Test
    fun `호스트가 제한 시간 안에 뜨지 않으면 NotStarted, 늦게 뜬 호스트는 바로 닫힌다`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent, startTimeoutMillis = 1_000) }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertSame(GoogleSignInHostResult.NotStarted, pending.await())
        val late = startHost()
        assertTrue(late.get().isFinishing)
        assertEquals(0, drainSignInStarts())
    }

    @Test
    fun `요청 코루틴이 취소되면 호스트를 닫고 다음 요청을 받는다`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val host = startHost()
        runCurrent()
        pending.cancel()
        runCurrent()
        assertTrue(host.get().isFinishing)

        val next = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        assertFalse("다음 요청이 Busy 로 막히면 안 된다", next.isCompleted)
        next.cancel()
    }

    @Test
    fun `지난 요청의 호스트는 새 요청을 가로채지 못한다`() = runTest(StandardTestDispatcher()) {
        val old = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val oldStart = drainStarts().single { it.component?.className == GoogleSignInHostActivity::class.java.name }
        old.cancel()
        runCurrent()

        val fresh = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val freshStart = drainStarts().single { it.component?.className == GoogleSignInHostActivity::class.java.name }
        val stale = Robolectric.buildActivity(GoogleSignInHostActivity::class.java, oldStart).setup()
        assertTrue(stale.get().isFinishing)
        assertEquals(0, drainSignInStarts())
        assertFalse(fresh.isCompleted)
        val host = Robolectric.buildActivity(GoogleSignInHostActivity::class.java, freshStart).setup()
        assertEquals(1, drainSignInStarts())
        shadowOf(host.get()).receiveResult(signInIntent, Activity.RESULT_OK, null)
        runCurrent()
        assertTrue(fresh.await() is GoogleSignInHostResult.ActivityResult)
    }

    @Test
    fun `기다리는 요청 없이 뜬 호스트(프로세스 재시작)는 아무것도 띄우지 않고 닫힌다`() {
        val intent = Intent(RuntimeEnvironment.getApplication(), GoogleSignInHostActivity::class.java)
            .putExtra(GoogleSignInHost.EXTRA_SIGN_IN_INTENT, signInIntent)
            .putExtra(GoogleSignInHost.EXTRA_REQUEST_ID, 777L)
        val host = Robolectric.buildActivity(GoogleSignInHostActivity::class.java, intent).setup()
        assertTrue(host.get().isFinishing)
        assertEquals(0, drainSignInStarts())
    }

    @Test
    fun `호스트에 계정 선택 인텐트가 없으면 실패로 끝낸다`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val hostStart = drainStarts().single { it.component?.className == GoogleSignInHostActivity::class.java.name }
        hostStart.removeExtra(GoogleSignInHost.EXTRA_SIGN_IN_INTENT)
        val host = Robolectric.buildActivity(GoogleSignInHostActivity::class.java, hostStart).setup()
        runCurrent()
        assertTrue(host.get().isFinishing)
        val r = pending.await() as GoogleSignInHostResult.Failed
        assertTrue(r.error is IllegalStateException)
    }

    @Test
    fun `계정 선택 화면을 띄우다 던지면 앱을 죽이지 않고 실패로 넘긴다`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val hostStart = drainStarts().single { it.component?.className == GoogleSignInHostActivity::class.java.name }
        // 계정 선택 인텐트를 받을 Activity 가 없으면 startActivityForResult 가 ActivityNotFoundException 을 던진다
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val controller = Robolectric.buildActivity(GoogleSignInHostActivity::class.java, hostStart).setup()
        runCurrent()
        assertTrue(controller.get().isFinishing)
        val r = pending.await() as GoogleSignInHostResult.Failed
        assertTrue(r.error is ActivityNotFoundException)
    }

    @Test
    fun `다른 요청 코드의 결과는 무시한다`() = runTest(StandardTestDispatcher()) {
        val pending = async { GoogleSignInHost.launch(caller, signInIntent) }
        runCurrent()
        val host = startHost()
        val other = Intent("other")
        host.get().startActivityForResult(other, 1)
        shadowOf(host.get()).receiveResult(other, Activity.RESULT_OK, null)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertFalse(host.get().isFinishing)
        pending.cancel()
    }

    /** 지금까지 시작된 Activity 인텐트를 전부 꺼낸다(목록을 비운다). */
    private fun drainStarts(): List<Intent> {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        return generateSequence { app.nextStartedActivity }.toList()
    }

    private fun drainSignInStarts(): Int = drainStarts().count { it.action == SIGN_IN_ACTION }

    private companion object {
        const val SIGN_IN_ACTION = "test.GOOGLE_SIGN_IN"
    }
}
