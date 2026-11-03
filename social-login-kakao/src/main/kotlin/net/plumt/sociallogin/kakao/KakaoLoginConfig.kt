package net.plumt.sociallogin.kakao

/**
 * 카카오 로그인 설정.
 *
 * @property nativeAppKey 카카오 디벨로퍼스 > 내 애플리케이션 > 앱 키 의 **네이티브 앱 키**. 앱이 런타임에 넣는다
 *   (예: `BuildConfig` 나 원격 설정). 이 라이브러리는 키를 갖고 있지 않다. 같은 키가 매니페스트 스킴
 *   `kakao{네이티브 앱 키}` 에도 들어가야 한다 — README 참고.
 * @property useKakaoTalk true 면 카카오톡이 설치돼 있을 때 카카오톡 앱 로그인을 먼저 시도하고, 사용자 취소가 아닌
 *   실패면 카카오계정(웹) 로그인으로 넘어간다. false 면 처음부터 카카오계정 로그인이다.
 * @throws IllegalArgumentException [nativeAppKey] 가 1..[MAX_KEY_LENGTH] 자 영숫자가 아닐 때.
 *   메시지에는 키 값 대신 길이만 넣는다.
 */
public class KakaoLoginConfig(
    public val nativeAppKey: String,
    public val useKakaoTalk: Boolean = true,
) {

    init {
        require(nativeAppKey.length in 1..MAX_KEY_LENGTH && nativeAppKey.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            "nativeAppKey 는 1..$MAX_KEY_LENGTH 자 영숫자여야 한다: 받은 길이 ${nativeAppKey.length} " +
                "(카카오 디벨로퍼스의 네이티브 앱 키를 넣는다)"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KakaoLoginConfig && other.nativeAppKey == nativeAppKey && other.useKakaoTalk == useKakaoTalk

    override fun hashCode(): Int = 31 * nativeAppKey.hashCode() + useKakaoTalk.hashCode()

    /** 앱 키는 가린다. */
    override fun toString(): String = "KakaoLoginConfig(nativeAppKey=<redacted>, useKakaoTalk=$useKakaoTalk)"

    public companion object {
        /** [nativeAppKey] 의 최대 길이. */
        public const val MAX_KEY_LENGTH: Int = 128
    }
}
