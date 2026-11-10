package net.plumt.sociallogin

/**
 * 소셜 로그인 동작의 결과. 실패는 예외가 아니라 값이다.
 *
 * - [Success] — 동작이 끝났다.
 * - [Cancelled] — 사용자가 로그인 화면을 닫았다. 오류 안내를 띄우지 않는다.
 * - [Failure] — 실패. [Failure.kind] 로 갈래를 나눈다.
 *
 * 호출 코루틴이 취소되면 결과를 만들지 않고 `CancellationException` 이 그대로 퍼진다.
 * 사용자 취소([Cancelled])와 코루틴 취소는 다른 것이다.
 */
public sealed interface SocialLoginResult<out T> {

    /** 성공. [value] 는 동작마다 다르다(로그인: [SocialAccount], 갱신: [SocialToken], 나머지: [Unit]). */
    public class Success<out T>(public val value: T) : SocialLoginResult<T> {
        override fun equals(other: Any?): Boolean = other is Success<*> && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Success($value)"
    }

    /** 사용자가 로그인 화면을 닫거나 뒤로 갔다. */
    public object Cancelled : SocialLoginResult<Nothing> {
        override fun toString(): String = "Cancelled"
    }

    /**
     * 실패.
     *
     * @property kind 실패 갈래.
     * @property message 개발자용 설명(한국어). 토큰·이메일·키 값은 넣지 않는다. 사용자에게 그대로 보이지 말고
     *   [kind] 로 문구를 고른다.
     * @property providerId 실패한 수단. 수단과 무관한 실패면 null.
     * @property vendorCode 벤더 SDK 의 원래 오류 코드(예: `AuthError.InvalidGrant`). 진단·로그용. 없으면 null.
     * @property cause 원래 예외. 없으면 null.
     * @throws IllegalArgumentException [message] 가 공백뿐이거나 [vendorCode] 가 공백뿐일 때.
     */
    public class Failure(
        public val kind: FailureKind,
        public val message: String,
        public val providerId: ProviderId?,
        public val vendorCode: String?,
        public val cause: Throwable?,
    ) : SocialLoginResult<Nothing> {

        /** 수단·벤더 코드·원인 없는 실패. */
        public constructor(kind: FailureKind, message: String) : this(kind, message, null, null, null)

        /** 벤더 코드·원인 없는 [providerId] 의 실패. */
        public constructor(kind: FailureKind, message: String, providerId: ProviderId?) :
            this(kind, message, providerId, null, null)

        init {
            require(message.isNotBlank()) { "message 가 공백뿐이다(길이 ${message.length})" }
            require(vendorCode == null || vendorCode.isNotBlank()) {
                "vendorCode 가 공백뿐이다(길이 ${vendorCode?.length}). 없으면 null 을 넣는다"
            }
        }

        /** [providerId] 만 바꾼 사본. */
        public fun withProviderId(providerId: ProviderId): Failure =
            Failure(kind, message, providerId, vendorCode, cause)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Failure) return false
            return kind == other.kind &&
                message == other.message &&
                providerId == other.providerId &&
                vendorCode == other.vendorCode &&
                cause == other.cause
        }

        override fun hashCode(): Int {
            var result = kind.hashCode()
            result = 31 * result + message.hashCode()
            result = 31 * result + providerId.hashCode()
            result = 31 * result + vendorCode.hashCode()
            result = 31 * result + cause.hashCode()
            return result
        }

        override fun toString(): String =
            "Failure(kind=$kind, message=$message, providerId=${providerId?.value}, vendorCode=$vendorCode, " +
                "cause=${cause?.javaClass?.name})"
    }
}

/** 성공이면 값, 아니면 null. */
public fun <T> SocialLoginResult<T>.getOrNull(): T? = (this as? SocialLoginResult.Success<T>)?.value

/** 성공 값만 [transform] 으로 바꾼다. 취소·실패는 그대로 둔다. */
public inline fun <T, R> SocialLoginResult<T>.map(transform: (T) -> R): SocialLoginResult<R> = when (this) {
    is SocialLoginResult.Success -> SocialLoginResult.Success(transform(value))
    SocialLoginResult.Cancelled -> SocialLoginResult.Cancelled
    is SocialLoginResult.Failure -> this
}
