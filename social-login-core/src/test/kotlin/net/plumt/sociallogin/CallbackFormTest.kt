package net.plumt.sociallogin

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class CallbackFormTest {

    private val kakao = FakeProvider(ProviderId.KAKAO)
    private val manager: SocialLoginManager<String> = SocialLoginManager.create(SocialLoginConfig.builder<String>().register(kakao).build())

    /** 받은 작업을 큐에 쌓고 테스트가 [drain] 할 때 돌리는 실행기 — 메인 스레드 실행기 흉내. */
    private class QueueExecutor : Executor {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun execute(command: Runnable) {
            tasks.put(command)
        }

        fun awaitAndRun() {
            requireNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "실행기에 작업이 오지 않았다" }.run()
        }
    }

    @Test
    fun `콜백 signIn 은 suspend 형태의 결과를 실행기에서 한 번 준다`() {
        val executor = QueueExecutor()
        val results = mutableListOf<SocialLoginResult<SocialAccount>>()
        val handle = manager.signIn(ProviderId.KAKAO, "host", SignInOptions.DEFAULT, executor) { results += it }
        executor.awaitAndRun()
        assertEquals(1, results.size)
        assertTrue(results.single() is SocialLoginResult.Success)
        assertEquals(listOf("signIn:host"), kakao.calls)
        assertFalse(handle.isActive)
        handle.cancel() // 끝난 뒤 취소는 아무것도 하지 않는다
    }

    @Test
    fun `signOut·unlink·refreshToken 콜백도 같은 로직을 쓴다`() {
        val executor = Executor { it.run() }
        val latch = CountDownLatch(4)
        val got = java.util.Collections.synchronizedList(mutableListOf<Any>())
        manager.signOut(ProviderId.KAKAO, executor) { got += it; latch.countDown() }
        manager.unlink(ProviderId.KAKAO, executor) { got += it; latch.countDown() }
        manager.refreshToken(ProviderId.KAKAO, executor) { got += it; latch.countDown() }
        manager.signOut(ProviderId.NAVER, executor) { got += it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val naver = got.filterIsInstance<SocialLoginResult.Failure>().single()
        assertEquals(FailureKind.PROVIDER_NOT_REGISTERED, naver.kind)
    }

    @Test
    fun `취소하면 콜백을 부르지 않고 Provider 코루틴을 취소한다`() {
        val started = CountDownLatch(1)
        val never = CompletableDeferred<SocialLoginResult<SocialAccount>>()
        val cancelled = CountDownLatch(1)
        kakao.signInResult = { _, _ ->
            started.countDown()
            try {
                never.await()
            } finally {
                cancelled.countDown()
            }
        }
        val executor = QueueExecutor()
        val handle = manager.signIn(ProviderId.KAKAO, "host", SignInOptions.DEFAULT, executor) {
            throw AssertionError("취소했는데 콜백이 불렸다")
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(handle.isActive)
        handle.cancel()
        assertFalse(handle.isActive)
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertEquals(null, executor.tasks.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `실행기에서 차례를 기다리는 사이 취소하면 부르지 않는다`() {
        val executor = QueueExecutor()
        var called = false
        val handle = manager.signIn(ProviderId.KAKAO, "host", SignInOptions.DEFAULT, executor) { called = true }
        val task = requireNotNull(executor.tasks.poll(5, TimeUnit.SECONDS))
        handle.cancel()
        task.run()
        assertFalse(called)
    }

    @Test
    fun `직접 구현한 Manager 가 던지면 실패로 감싼다`() {
        // 위임(by)은 기본 구현 멤버까지 넘기므로 쓰지 않고 추상 멤버만 직접 구현한다
        val throwing = object : SocialLoginManager<String> {
            override val providerIds: List<ProviderId> = listOf(ProviderId.KAKAO)
            override fun isRegistered(providerId: ProviderId): Boolean = true
            override fun capabilitiesOf(providerId: ProviderId): Set<Capability> = emptySet()
            override suspend fun signIn(providerId: ProviderId, host: String, options: SignInOptions) =
                throw UnsupportedOperationException()
            override suspend fun signOut(providerId: ProviderId): SocialLoginResult<Unit> =
                throw java.io.IOException("망")
            override suspend fun unlink(providerId: ProviderId): SocialLoginResult<Unit> =
                throw UnsupportedOperationException()
            override suspend fun refreshToken(providerId: ProviderId): SocialLoginResult<SocialToken> =
                throw UnsupportedOperationException()
        }
        val executor = QueueExecutor()
        var result: SocialLoginResult<Unit>? = null
        throwing.signOut(ProviderId.KAKAO, executor) { result = it }
        executor.awaitAndRun()
        assertEquals(FailureKind.NETWORK, (result as SocialLoginResult.Failure).kind)
    }

    @Test
    fun `실행기가 거절하면 결과를 버리고 핸들을 끝낸다`() {
        val rejected = CountDownLatch(1)
        val executor = Executor {
            rejected.countDown()
            throw RejectedExecutionException("닫힘")
        }
        val handle = manager.signOut(ProviderId.KAKAO, executor) { throw AssertionError("불리면 안 된다") }
        assertTrue(rejected.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (handle.isActive && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(handle.isActive)
        assertTrue(handle.toString().startsWith("SocialLoginCallHandle("))
    }
}
