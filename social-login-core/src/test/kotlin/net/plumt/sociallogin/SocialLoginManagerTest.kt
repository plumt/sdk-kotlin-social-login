package net.plumt.sociallogin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** 호출을 기록하고 결과를 바꿔 끼울 수 있는 가짜 Provider. */
internal class FakeProvider(
    override val id: ProviderId,
    override val capabilities: Set<Capability> = Capability.entries.toSet(),
) : SocialLoginProvider<String> {
    val calls = mutableListOf<String>()
    var signInResult: suspend (String, SignInOptions) -> SocialLoginResult<SocialAccount> =
        { _, _ -> SocialLoginResult.Success(SocialAccount(id, SocialToken("a", null))) }
    var signOutResult: suspend () -> SocialLoginResult<Unit> = { SocialLoginResult.Success(Unit) }
    var unlinkResult: suspend () -> SocialLoginResult<Unit> = { SocialLoginResult.Success(Unit) }
    var refreshResult: suspend () -> SocialLoginResult<SocialToken> = { SocialLoginResult.Success(SocialToken("b", null)) }

    override suspend fun signIn(host: String, options: SignInOptions): SocialLoginResult<SocialAccount> {
        calls += "signIn:$host"
        return signInResult(host, options)
    }

    override suspend fun signOut(): SocialLoginResult<Unit> {
        calls += "signOut"
        return signOutResult()
    }

    override suspend fun unlink(): SocialLoginResult<Unit> {
        calls += "unlink"
        return unlinkResult()
    }

    override suspend fun refreshToken(): SocialLoginResult<SocialToken> {
        calls += "refresh"
        return refreshResult()
    }
}

class SocialLoginManagerTest {

    private val kakao = FakeProvider(ProviderId.KAKAO)
    private val google = FakeProvider(ProviderId.GOOGLE, capabilities = setOf(Capability.SIGN_OUT))

    private fun manager(policy: ConcurrencyPolicy = ConcurrencyPolicy.REJECT): SocialLoginManager<String> =
        SocialLoginManager.create(SocialLoginConfig.builder<String>().register(kakao).register(google).concurrencyPolicy(policy).build())

    @Test
    fun `등록 순서·등록 여부·지원 동작`() {
        val m = manager()
        assertEquals(listOf(ProviderId.KAKAO, ProviderId.GOOGLE), m.providerIds)
        assertTrue(m.isRegistered(ProviderId.KAKAO))
        assertFalse(m.isRegistered(ProviderId.NAVER))
        assertEquals(Capability.entries.toSet(), m.capabilitiesOf(ProviderId.KAKAO))
        assertEquals(setOf(Capability.SIGN_OUT), m.capabilitiesOf(ProviderId.GOOGLE))
        assertEquals(emptySet<Capability>(), m.capabilitiesOf(ProviderId.NAVER))
    }

    @Test
    fun `같은 수단을 두 번 등록하면 거절`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SocialLoginConfig.builder<String>().register(kakao).register(FakeProvider(ProviderId.KAKAO))
        }
        assertTrue(e.message!!.contains("kakao"))
        assertTrue(e.message!!.contains("[kakao]"))
    }

    @Test
    fun `build 뒤 조립기를 바꿔도 이미 만든 Manager 는 그대로다`() {
        val builder = SocialLoginConfig.builder<String>().register(kakao)
        val m = SocialLoginManager.create(builder.build())
        builder.register(google)
        assertEquals(listOf(ProviderId.KAKAO), m.providerIds)
        assertEquals(listOf(ProviderId.KAKAO, ProviderId.GOOGLE), SocialLoginManager.create(builder.build()).providerIds)
    }

    @Test
    fun `빈 Manager 는 모든 호출이 PROVIDER_NOT_REGISTERED`() = runTest {
        val m = SocialLoginManager.create(SocialLoginConfig.builder<String>().build())
        assertEquals(emptyList<ProviderId>(), m.providerIds)
        listOf(
            m.signIn(ProviderId.KAKAO, "host"),
            m.signOut(ProviderId.KAKAO),
            m.unlink(ProviderId.KAKAO),
            m.refreshToken(ProviderId.KAKAO),
        ).forEach {
            val f = it as SocialLoginResult.Failure
            assertEquals(FailureKind.PROVIDER_NOT_REGISTERED, f.kind)
            assertEquals(ProviderId.KAKAO, f.providerId)
            assertTrue(f.message.contains("kakao"))
        }
    }

    @Test
    fun `로그인은 호스트와 옵션을 그대로 넘긴다`() = runTest {
        var seen: SignInOptions? = null
        kakao.signInResult = { _, options ->
            seen = options
            SocialLoginResult.Success(SocialAccount(ProviderId.KAKAO, SocialToken("a", null)))
        }
        val m = manager()
        val result = m.signIn(ProviderId.KAKAO, "activity-1", SignInOptions("n1"))
        assertEquals(ProviderId.KAKAO, (result as SocialLoginResult.Success).value.providerId)
        assertEquals(listOf("signIn:activity-1"), kakao.calls)
        assertEquals(SignInOptions("n1"), seen)
        m.signIn(ProviderId.KAKAO, "activity-2")
        assertEquals(SignInOptions.DEFAULT, seen)
        assertEquals(emptyList<String>(), google.calls)
    }

    @Test
    fun `등록하지 않은 수단은 Provider 를 부르지 않는다`() = runTest {
        val f = manager().signIn(ProviderId.NAVER, "h") as SocialLoginResult.Failure
        assertEquals(FailureKind.PROVIDER_NOT_REGISTERED, f.kind)
        assertTrue(f.message.contains("[kakao, google]"))
        assertTrue(kakao.calls.isEmpty() && google.calls.isEmpty())
    }

    @Test
    fun `선언하지 않은 동작은 UNSUPPORTED 이고 Provider 를 부르지 않는다`() = runTest {
        val m = manager()
        val unlink = m.unlink(ProviderId.GOOGLE) as SocialLoginResult.Failure
        assertEquals(FailureKind.UNSUPPORTED, unlink.kind)
        assertTrue(unlink.message.contains("UNLINK"))
        val refresh = m.refreshToken(ProviderId.GOOGLE) as SocialLoginResult.Failure
        assertEquals(FailureKind.UNSUPPORTED, refresh.kind)
        assertEquals(emptyList<String>(), google.calls)
        assertEquals(SocialLoginResult.Success(Unit), m.signOut(ProviderId.GOOGLE))
        assertEquals(listOf("signOut"), google.calls)
    }

    @Test
    fun `로그아웃·연결 해제·갱신은 Provider 결과를 그대로 돌려준다`() = runTest {
        val m = manager()
        assertEquals(SocialLoginResult.Success(Unit), m.signOut(ProviderId.KAKAO))
        assertEquals(SocialLoginResult.Success(Unit), m.unlink(ProviderId.KAKAO))
        assertEquals(SocialLoginResult.Success(SocialToken("b", null)), m.refreshToken(ProviderId.KAKAO))
        assertEquals(listOf("signOut", "unlink", "refresh"), kakao.calls)
        kakao.signOutResult = { SocialLoginResult.Cancelled }
        assertSame(SocialLoginResult.Cancelled, m.signOut(ProviderId.KAKAO))
    }

    @Test
    fun `providerId 가 빈 실패에는 수단을 채운다`() = runTest {
        kakao.unlinkResult = { SocialLoginResult.Failure(FailureKind.AUTHENTICATION, "m", vendorCode = "V", providerId = null, cause = null) }
        val f = manager().unlink(ProviderId.KAKAO) as SocialLoginResult.Failure
        assertEquals(ProviderId.KAKAO, f.providerId)
        assertEquals(FailureKind.AUTHENTICATION, f.kind)
        assertEquals("V", f.vendorCode)
    }

    @Test
    fun `providerId 가 있는 실패는 그대로 둔다`() = runTest {
        val original = SocialLoginResult.Failure(FailureKind.AUTHENTICATION, "m", providerId = ProviderId("other"))
        kakao.unlinkResult = { original }
        assertSame(original, manager().unlink(ProviderId.KAKAO))
    }

    @Test
    fun `다른 수단의 계정을 돌려주면 UNKNOWN`() = runTest {
        kakao.signInResult = { _, _ -> SocialLoginResult.Success(SocialAccount(ProviderId.GOOGLE, SocialToken("a", null))) }
        val f = manager().signIn(ProviderId.KAKAO, "h") as SocialLoginResult.Failure
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertTrue(f.message.contains("google"))
    }

    @Test
    fun `Provider 가 던진 예외는 분류해 감싸고 원인을 보존한다`() = runTest {
        val io = IOException("net")
        kakao.signInResult = { _, _ -> throw io }
        val f = manager().signIn(ProviderId.KAKAO, "h") as SocialLoginResult.Failure
        assertEquals(FailureKind.NETWORK, f.kind)
        assertSame(io, f.cause)
        assertEquals(ProviderId.KAKAO, f.providerId)
        assertTrue(f.message.contains("signIn"))
        assertTrue(f.message.contains("java.io.IOException"))

        val ise = IllegalStateException("boom")
        kakao.refreshResult = { throw ise }
        val g = manager().refreshToken(ProviderId.KAKAO) as SocialLoginResult.Failure
        assertEquals(FailureKind.UNKNOWN, g.kind)
        assertSame(ise, g.cause)
    }

    @Test
    fun `Provider 의 Error 는 감싸지 않고 퍼진다`() = runTest {
        val m = manager()
        kakao.signOutResult = { throw StackOverflowError() }
        try {
            m.signOut(ProviderId.KAKAO)
            fail()
        } catch (_: StackOverflowError) {
        }
        // 잠금이 풀렸다
        assertEquals(SocialLoginResult.Success(Unit), m.signOut(ProviderId.GOOGLE))
    }

    @Test
    fun `코루틴 취소는 결과로 바꾸지 않고 다시 던진다`() = runTest {
        val m = manager()
        kakao.signInResult = { _, _ -> throw CancellationException("cancel") }
        try {
            m.signIn(ProviderId.KAKAO, "h")
            fail()
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
        // 취소 뒤 잠금이 풀려 다음 호출이 된다
        kakao.signInResult = { _, _ -> SocialLoginResult.Cancelled }
        assertSame(SocialLoginResult.Cancelled, m.signIn(ProviderId.KAKAO, "h"))
    }

    @Test
    fun `REJECT — 진행 중이면 두 번째 호출은 BUSY, 끝나면 다시 받는다`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        kakao.signInResult = { _, _ ->
            gate.await()
            SocialLoginResult.Cancelled
        }
        val m = manager(ConcurrencyPolicy.REJECT)
        val first = async { m.signIn(ProviderId.KAKAO, "h") }
        val busy = m.signIn(ProviderId.KAKAO, "h") as SocialLoginResult.Failure
        assertEquals(FailureKind.BUSY, busy.kind)
        assertTrue(busy.kind.retryable)
        assertTrue(busy.message.contains("signIn(kakao)"))
        // 다른 수단·다른 동작도 같은 잠금을 쓴다
        assertEquals(FailureKind.BUSY, (m.signOut(ProviderId.GOOGLE) as SocialLoginResult.Failure).kind)
        assertEquals(FailureKind.BUSY, (m.unlink(ProviderId.KAKAO) as SocialLoginResult.Failure).kind)
        assertEquals(FailureKind.BUSY, (m.refreshToken(ProviderId.KAKAO) as SocialLoginResult.Failure).kind)
        assertEquals(listOf("signIn:h"), kakao.calls)
        gate.complete(Unit)
        assertSame(SocialLoginResult.Cancelled, first.await())
        assertSame(SocialLoginResult.Cancelled, m.signIn(ProviderId.KAKAO, "h"))
    }

    @Test
    fun `REJECT — 잠금 전에 끝나는 검사(미등록·미지원)는 BUSY 가 아니다`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        kakao.signInResult = { _, _ ->
            gate.await()
            SocialLoginResult.Cancelled
        }
        val m = manager()
        val first = async { m.signIn(ProviderId.KAKAO, "h") }
        assertEquals(FailureKind.PROVIDER_NOT_REGISTERED, (m.signIn(ProviderId.NAVER, "h") as SocialLoginResult.Failure).kind)
        assertEquals(FailureKind.UNSUPPORTED, (m.unlink(ProviderId.GOOGLE) as SocialLoginResult.Failure).kind)
        gate.complete(Unit)
        first.await()
    }

    @Test
    fun `SERIALIZE — 겹친 호출은 차례로 실행된다`() = runTest(StandardTestDispatcher()) {
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        kakao.signInResult = { host, _ ->
            order += "start:$host"
            if (host == "1") gate.await()
            order += "end:$host"
            SocialLoginResult.Cancelled
        }
        val m = manager(ConcurrencyPolicy.SERIALIZE)
        val a = async { m.signIn(ProviderId.KAKAO, "1") }
        val b = async { m.signIn(ProviderId.KAKAO, "2") }
        runCurrent()
        assertEquals(listOf("start:1"), order)
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(SocialLoginResult.Cancelled, a.await())
        assertSame(SocialLoginResult.Cancelled, b.await())
        assertEquals(listOf("start:1", "end:1", "start:2", "end:2"), order)
    }

    @Test
    fun `SERIALIZE — 기다리다 취소된 호출은 실행되지 않는다`() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        kakao.signInResult = { host, _ ->
            if (host == "1") gate.await()
            SocialLoginResult.Cancelled
        }
        val m = manager(ConcurrencyPolicy.SERIALIZE)
        val a = async { m.signIn(ProviderId.KAKAO, "1") }
        val b = launch { m.signIn(ProviderId.KAKAO, "2") }
        runCurrent()
        b.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        a.await()
        assertTrue(b.isCancelled)
        assertEquals(listOf("signIn:1"), kakao.calls)
    }

    @Test
    fun `진행 중인 호출을 취소하면 잠금이 풀린다`() = runTest(StandardTestDispatcher()) {
        val never = CompletableDeferred<Unit>()
        kakao.signInResult = { _, _ ->
            never.await()
            SocialLoginResult.Cancelled
        }
        val m = manager(ConcurrencyPolicy.REJECT)
        val job = launch { m.signIn(ProviderId.KAKAO, "1") }
        runCurrent()
        assertEquals(FailureKind.BUSY, (m.signOut(ProviderId.KAKAO) as SocialLoginResult.Failure).kind)
        job.cancel()
        advanceUntilIdle()
        assertEquals(SocialLoginResult.Success(Unit), m.signOut(ProviderId.KAKAO))
    }

    @Test
    fun `예외 뒤에도 잠금이 풀린다`() = runTest {
        val m = manager()
        kakao.signInResult = { _, _ -> throw IllegalStateException() }
        assertEquals(FailureKind.UNKNOWN, (m.signIn(ProviderId.KAKAO, "h") as SocialLoginResult.Failure).kind)
        assertEquals(SocialLoginResult.Success(Unit), m.signOut(ProviderId.KAKAO))
        val s = manager(ConcurrencyPolicy.SERIALIZE)
        assertEquals(FailureKind.UNKNOWN, (s.signIn(ProviderId.KAKAO, "h") as SocialLoginResult.Failure).kind)
        assertEquals(SocialLoginResult.Success(Unit), s.signOut(ProviderId.KAKAO))
    }

    @Test
    fun `Manager 는 contravariant 호스트를 받는다`() = runTest {
        val anyHost = object : SocialLoginProvider<Any> {
            override val id = ProviderId("apple")
            override val capabilities = emptySet<Capability>()
            override suspend fun signIn(host: Any, options: SignInOptions): SocialLoginResult<SocialAccount> =
                SocialLoginResult.Success(SocialAccount(id, SocialToken(null, host.toString())))
        }
        val m: SocialLoginManager<String> = SocialLoginManager.create(SocialLoginConfig.builder<String>().register(anyHost).build())
        val acc = (m.signIn(ProviderId("apple"), "h") as SocialLoginResult.Success).value
        assertEquals("h", acc.token.idToken)
        assertEquals(FailureKind.UNSUPPORTED, (m.signOut(ProviderId("apple")) as SocialLoginResult.Failure).kind)
    }
}
