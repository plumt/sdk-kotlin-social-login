package net.plumt.sociallogin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdTest {

    @Test
    fun `잘 알려진 수단 상수`() {
        assertEquals("kakao", ProviderId.KAKAO.value)
        assertEquals("naver", ProviderId.NAVER.value)
        assertEquals("google", ProviderId.GOOGLE.value)
        assertEquals(32, ProviderId.MAX_LENGTH)
    }

    @Test
    fun `경계값 — 1자와 최대 길이는 받는다`() {
        assertEquals("a", ProviderId("a").value)
        val max = "a" + "b".repeat(ProviderId.MAX_LENGTH - 1)
        assertEquals(max, ProviderId(max).value)
        assertEquals("apple_id-2", ProviderId("apple_id-2").value)
    }

    @Test
    fun `형식이 틀리면 IllegalArgumentException 과 받은 값`() {
        listOf("", " ", "Kakao", "1kakao", "_a", "-a", "ka kao", "kakao!", "카카오", "a\n", "a".repeat(33)).forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) { ProviderId(bad) }
            assertTrue(bad, e.message!!.contains("길이 ${bad.length}"))
            assertTrue(e.message!!.contains("1..32"))
        }
    }

    @Test
    fun `매우 긴 입력은 메시지에서 잘린다`() {
        val long = "a".repeat(100_000)
        val e = assertThrows(IllegalArgumentException::class.java) { ProviderId(long) }
        assertTrue(e.message!!.length < 300)
        assertTrue(e.message!!.contains("…"))
        assertTrue(e.message!!.contains("길이 100000"))
    }

    @Test
    fun `값이 같으면 같다`() {
        assertEquals(ProviderId("kakao"), ProviderId.KAKAO)
        assertEquals(ProviderId("kakao").hashCode(), ProviderId.KAKAO.hashCode())
        assertNotEquals(ProviderId.KAKAO, ProviderId.NAVER)
        assertNotEquals(ProviderId.KAKAO as Any, "kakao")
        assertEquals("ProviderId(kakao)", ProviderId.KAKAO.toString())
    }
}

class SocialTokenTest {

    @Test
    fun `액세스 토큰만 또는 ID 토큰만으로 만든다`() {
        val a = SocialToken(accessToken = "a", idToken = null)
        assertEquals("a", a.accessToken)
        assertNull(a.idToken)
        assertNull(a.refreshToken)
        assertNull(a.accessTokenExpiresAtMillis)
        assertNull(a.refreshTokenExpiresAtMillis)
        assertEquals(emptySet<String>(), a.scopes)
        val i = SocialToken(accessToken = null, idToken = "i")
        assertEquals("i", i.idToken)
    }

    @Test
    fun `둘 다 null 이면 거절`() {
        val e = assertThrows(IllegalArgumentException::class.java) { SocialToken(null, null) }
        assertTrue(e.message!!.contains("둘 다 null"))
    }

    @Test
    fun `공백뿐인 토큰은 값 대신 길이만 메시지에 넣고 거절`() {
        listOf("", " ", "\t\n").forEach { blank ->
            val e1 = assertThrows(IllegalArgumentException::class.java) { SocialToken(blank, "i") }
            assertTrue(e1.message!!.startsWith("accessToken"))
            assertTrue(e1.message!!.contains("길이 ${blank.length}"))
            val e2 = assertThrows(IllegalArgumentException::class.java) { SocialToken("a", blank) }
            assertTrue(e2.message!!.startsWith("idToken"))
            val e3 = assertThrows(IllegalArgumentException::class.java) { SocialToken("a", null, refreshToken = blank, accessTokenExpiresAtMillis = null, refreshTokenExpiresAtMillis = null, scopes = emptySet()) }
            assertTrue(e3.message!!.startsWith("refreshToken"))
        }
    }

    @Test
    fun `만료 시각 경계값`() {
        SocialToken("a", null, accessTokenExpiresAtMillis = 0, refreshTokenExpiresAtMillis = 0, refreshToken = null, scopes = emptySet())
        SocialToken("a", null, accessTokenExpiresAtMillis = Long.MAX_VALUE, refreshTokenExpiresAtMillis = Long.MAX_VALUE, refreshToken = null, scopes = emptySet())
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            SocialToken("a", null, accessTokenExpiresAtMillis = -1, refreshToken = null, refreshTokenExpiresAtMillis = null, scopes = emptySet())
        }
        assertTrue(e1.message!!.contains("받은 값 -1"))
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            SocialToken("a", null, refreshTokenExpiresAtMillis = Long.MIN_VALUE, refreshToken = null, accessTokenExpiresAtMillis = null, scopes = emptySet())
        }
        assertTrue(e2.message!!.contains("refreshTokenExpiresAtMillis"))
    }

    @Test
    fun `공백 scope 거절, 유니코드 scope 허용`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SocialToken("a", null, scopes = setOf("profile", " "), refreshToken = null, accessTokenExpiresAtMillis = null, refreshTokenExpiresAtMillis = null)
        }
        assertTrue(e.message!!.contains("scopes"))
        assertEquals(setOf("프로필", "account_email"), SocialToken("a", null, scopes = setOf("프로필", "account_email"), refreshToken = null, accessTokenExpiresAtMillis = null, refreshTokenExpiresAtMillis = null).scopes)
    }

    @Test
    fun `만료 판정 — 모르면 false, 경계에서 만료`() {
        assertFalse(SocialToken("a", null).isAccessTokenExpired(Long.MAX_VALUE))
        val t = SocialToken("a", null, accessTokenExpiresAtMillis = 1_000, refreshToken = null, refreshTokenExpiresAtMillis = null, scopes = emptySet())
        assertFalse(t.isAccessTokenExpired(999))
        assertTrue(t.isAccessTokenExpired(1_000))
        assertTrue(t.isAccessTokenExpired(1_001))
        assertFalse(t.isAccessTokenExpired(Long.MIN_VALUE))
    }

    @Test
    fun `toString 은 토큰 값을 가린다`() {
        val secretA = "AT-" + "x".repeat(40)
        val secretI = "ID-" + "y".repeat(40)
        val secretR = "RT-" + "z".repeat(40)
        val s = SocialToken(secretA, secretI, secretR, 1, 2, setOf("openid")).toString()
        assertFalse(s.contains(secretA))
        assertFalse(s.contains(secretI))
        assertFalse(s.contains(secretR))
        assertTrue(s.contains("<redacted>"))
        assertTrue(s.contains("accessTokenExpiresAtMillis=1"))
        assertTrue(s.contains("scopes=[openid]"))
        assertTrue(SocialToken("a", null).toString().contains("idToken=null"))
    }

    @Test
    fun `equals·hashCode 는 모든 필드를 본다`() {
        val base = SocialToken("a", "i", "r", 1, 2, setOf("s"))
        assertEquals(base, SocialToken("a", "i", "r", 1, 2, setOf("s")))
        assertEquals(base.hashCode(), SocialToken("a", "i", "r", 1, 2, setOf("s")).hashCode())
        assertEquals(base, base)
        assertNotEquals(base, SocialToken("b", "i", "r", 1, 2, setOf("s")))
        assertNotEquals(base, SocialToken("a", "j", "r", 1, 2, setOf("s")))
        assertNotEquals(base, SocialToken("a", "i", "q", 1, 2, setOf("s")))
        assertNotEquals(base, SocialToken("a", "i", "r", 3, 2, setOf("s")))
        assertNotEquals(base, SocialToken("a", "i", "r", 1, 3, setOf("s")))
        assertNotEquals(base, SocialToken("a", "i", "r", 1, 2, setOf("t")))
        assertNotEquals(base as Any, "a")
        assertNotEquals(base as Any, null)
        // null 필드가 있어도 hashCode 가 동작한다
        SocialToken(null, "i").hashCode()
    }

    @Test
    fun `매우 긴 유니코드 토큰도 받는다`() {
        val long = "토큰🙂".repeat(50_000)
        assertEquals(long, SocialToken(long, null).accessToken)
    }
}

class SocialAccountTest {

    private val token = SocialToken("a", null)

    @Test
    fun `기본값은 null`() {
        val acc = SocialAccount(ProviderId.KAKAO, token)
        assertSame(token, acc.token)
        assertEquals(ProviderId.KAKAO, acc.providerId)
        assertNull(acc.email)
        assertNull(acc.displayName)
        assertNull(acc.profileImageUrl)
    }

    @Test
    fun `공백 문자열은 거절하고 길이만 알린다`() {
        val e1 = assertThrows(IllegalArgumentException::class.java) { SocialAccount(ProviderId.GOOGLE, token, email = " ", displayName = null, profileImageUrl = null) }
        assertTrue(e1.message!!.startsWith("email"))
        assertTrue(e1.message!!.contains("길이 1"))
        val e2 = assertThrows(IllegalArgumentException::class.java) { SocialAccount(ProviderId.GOOGLE, token, displayName = "", email = null, profileImageUrl = null) }
        assertTrue(e2.message!!.startsWith("displayName"))
        val e3 = assertThrows(IllegalArgumentException::class.java) {
            SocialAccount(ProviderId.GOOGLE, token, profileImageUrl = "\n", email = null, displayName = null)
        }
        assertTrue(e3.message!!.startsWith("profileImageUrl"))
    }

    @Test
    fun `toString 은 개인정보와 토큰을 가린다`() {
        val email = "person@example.com"
        val name = "홍길동"
        val url = "https://img.example.com/p.png"
        val s = SocialAccount(ProviderId.GOOGLE, SocialToken(null, "secret-id-token"), email, name, url).toString()
        assertFalse(s.contains(email))
        assertFalse(s.contains(name))
        assertFalse(s.contains(url))
        assertFalse(s.contains("secret-id-token"))
        assertTrue(s.contains("providerId=google"))
        assertTrue(SocialAccount(ProviderId.KAKAO, token).toString().contains("email=null"))
    }

    @Test
    fun `equals·hashCode 는 모든 필드를 본다`() {
        val base = SocialAccount(ProviderId.GOOGLE, token, "e", "d", "p")
        assertEquals(base, SocialAccount(ProviderId.GOOGLE, SocialToken("a", null), "e", "d", "p"))
        assertEquals(base.hashCode(), SocialAccount(ProviderId.GOOGLE, SocialToken("a", null), "e", "d", "p").hashCode())
        assertEquals(base, base)
        assertNotEquals(base, SocialAccount(ProviderId.KAKAO, token, "e", "d", "p"))
        assertNotEquals(base, SocialAccount(ProviderId.GOOGLE, SocialToken("b", null), "e", "d", "p"))
        assertNotEquals(base, SocialAccount(ProviderId.GOOGLE, token, "f", "d", "p"))
        assertNotEquals(base, SocialAccount(ProviderId.GOOGLE, token, "e", "x", "p"))
        assertNotEquals(base, SocialAccount(ProviderId.GOOGLE, token, "e", "d", "x"))
        assertNotEquals(base as Any, token)
        SocialAccount(ProviderId.GOOGLE, token).hashCode()
    }
}

class SignInOptionsTest {

    @Test
    fun `기본값`() {
        assertNull(SignInOptions(null).nonce)
        assertNull(SignInOptions.DEFAULT.nonce)
        assertEquals(SignInOptions(null), SignInOptions.DEFAULT)
        assertEquals(512, SignInOptions.MAX_NONCE_LENGTH)
    }

    @Test
    fun `nonce 경계값`() {
        assertEquals("n", SignInOptions("n").nonce)
        val max = "n".repeat(SignInOptions.MAX_NONCE_LENGTH)
        assertEquals(max, SignInOptions(max).nonce)
        assertEquals("논스🙂", SignInOptions("논스🙂").nonce)
    }

    @Test
    fun `nonce 가 비었거나 너무 길면 길이를 알리고 거절`() {
        val e1 = assertThrows(IllegalArgumentException::class.java) { SignInOptions("") }
        assertTrue(e1.message!!.contains("받은 길이 0"))
        val e2 = assertThrows(IllegalArgumentException::class.java) { SignInOptions("n".repeat(513)) }
        assertTrue(e2.message!!.contains("받은 길이 513"))
        assertTrue(e2.message!!.contains("1..512"))
    }

    @Test
    fun `nonce 에 공백 문자가 있으면 값 없이 거절`() {
        listOf(" ", "a b", "ab\n", "\tab", "a　b").forEach { bad ->
            val e = assertThrows(IllegalArgumentException::class.java) { SignInOptions(bad) }
            assertTrue(e.message!!.contains("공백 문자"))
            assertFalse(bad.isNotBlank() && e.message!!.contains(bad))
        }
    }

    @Test
    fun `equals·hashCode·toString`() {
        assertEquals(SignInOptions("n"), SignInOptions("n"))
        assertEquals(SignInOptions("n").hashCode(), SignInOptions("n").hashCode())
        assertNotEquals(SignInOptions("n"), SignInOptions("m"))
        assertNotEquals(SignInOptions("n") as Any, "n")
        assertFalse(SignInOptions("nonce-value-123").toString().contains("nonce-value-123"))
        assertEquals("SignInOptions(nonce=null)", SignInOptions.DEFAULT.toString())
        assertEquals("SignInOptions(nonce=<redacted>)", SignInOptions("x").toString())
    }
}
