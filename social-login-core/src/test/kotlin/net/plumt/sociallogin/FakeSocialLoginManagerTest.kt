package net.plumt.sociallogin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class FakeSocialLoginManagerTest {

    private val account = SocialAccount(ProviderId.KAKAO, SocialToken("a", null))
    private val fake = FakeSocialLoginManager<String>(listOf(ProviderId.KAKAO, ProviderId.GOOGLE))
    private val direct = Executor { it.run() }

    @Test
    fun `정한 결과를 돌려주고 호출을 기록한다`() = runTest {
        fake.setSignInResult(ProviderId.KAKAO, SocialLoginResult.Success(account))
        val options = SignInOptions("n")
        assertEquals(SocialLoginResult.Success(account), fake.signIn(ProviderId.KAKAO, "host", options))
        assertEquals(
            listOf(FakeSocialLoginManager.Call(FakeSocialLoginManager.SIGN_IN, ProviderId.KAKAO, options)),
            fake.calls,
        )
        fake.clearCalls()
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `등록·지원 여부는 실제 Manager 와 같이 판정한다`() = runTest {
        assertEquals(listOf(ProviderId.KAKAO, ProviderId.GOOGLE), fake.providerIds)
        assertTrue(fake.isRegistered(ProviderId.KAKAO))
        assertFalse(fake.isRegistered(ProviderId.NAVER))
        assertEquals(Capability.entries.toSet(), fake.capabilitiesOf(ProviderId.GOOGLE))
        assertEquals(emptySet<Capability>(), fake.capabilitiesOf(ProviderId.NAVER))

        fake.setCapabilities(ProviderId.GOOGLE, setOf(Capability.SIGN_OUT))
        val refresh = fake.refreshToken(ProviderId.GOOGLE) as SocialLoginResult.Failure
        assertEquals(FailureKind.UNSUPPORTED, refresh.kind)
        val naver = fake.signOut(ProviderId.NAVER) as SocialLoginResult.Failure
        assertEquals(FailureKind.PROVIDER_NOT_REGISTERED, naver.kind)
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `결과를 정하지 않으면 UNKNOWN 이다`() = runTest {
        assertEquals(FailureKind.UNKNOWN, (fake.unlink(ProviderId.KAKAO) as SocialLoginResult.Failure).kind)
        fake.setUnlinkResult(ProviderId.KAKAO, SocialLoginResult.Cancelled)
        assertEquals(SocialLoginResult.Cancelled, fake.unlink(ProviderId.KAKAO))
        fake.setSignOutResult(ProviderId.KAKAO, SocialLoginResult.Success(Unit))
        assertEquals(SocialLoginResult.Success(Unit), fake.signOut(ProviderId.KAKAO))
        val token = SocialToken("b", null)
        fake.setRefreshTokenResult(ProviderId.KAKAO, SocialLoginResult.Success(token))
        assertEquals(SocialLoginResult.Success(token), fake.refreshToken(ProviderId.KAKAO))
    }

    @Test
    fun `콜백 형태는 곧바로 실행기에 넘긴다`() {
        fake.setSignInResult(ProviderId.KAKAO, SocialLoginResult.Success(account))
        fake.setSignOutResult(ProviderId.KAKAO, SocialLoginResult.Success(Unit))
        fake.setUnlinkResult(ProviderId.KAKAO, SocialLoginResult.Success(Unit))
        fake.setRefreshTokenResult(ProviderId.KAKAO, SocialLoginResult.Success(SocialToken("b", null)))
        val got = mutableListOf<SocialLoginResult<*>>()
        val handle = fake.signIn(ProviderId.KAKAO, "host", SignInOptions.DEFAULT, direct) { got += it }
        fake.signOut(ProviderId.KAKAO, direct) { got += it }
        fake.unlink(ProviderId.KAKAO, direct) { got += it }
        fake.refreshToken(ProviderId.KAKAO, direct) { got += it }
        assertEquals(4, got.size)
        assertTrue(got.all { it is SocialLoginResult.Success })
        assertFalse(handle.isActive)
        assertEquals(
            listOf(
                FakeSocialLoginManager.SIGN_IN,
                FakeSocialLoginManager.SIGN_OUT,
                FakeSocialLoginManager.UNLINK,
                FakeSocialLoginManager.REFRESH_TOKEN,
            ),
            fake.calls.map { it.operation },
        )
    }

    @Test
    fun `잘못된 사용은 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            FakeSocialLoginManager<String>(listOf(ProviderId.KAKAO, ProviderId.KAKAO))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.setSignInResult(ProviderId.NAVER, SocialLoginResult.Cancelled)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.setCapabilities(ProviderId.NAVER, emptySet())
        }
    }

    @Test
    fun `Call 은 값으로 비교하고 toString 이 nonce 를 가린다`() {
        val a = FakeSocialLoginManager.Call(FakeSocialLoginManager.SIGN_IN, ProviderId.KAKAO, SignInOptions("secret"))
        val b = FakeSocialLoginManager.Call(FakeSocialLoginManager.SIGN_IN, ProviderId.KAKAO, SignInOptions("secret"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, FakeSocialLoginManager.Call(FakeSocialLoginManager.UNLINK, ProviderId.KAKAO, null))
        assertFalse(a.toString().contains("secret"))
        assertTrue(fake.toString().contains("kakao"))
    }
}
