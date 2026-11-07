# 네이버 로그인 SDK(NidOAuth) — 이 모듈을 의존하면 앱에 자동 적용된다.

# SDK 가 리플렉션·직렬화로 콜백·응답 타입을 다루므로 공개 API 를 보존한다
-keep public class com.navercorp.nid.** { *; }
-dontwarn com.navercorp.nid.**

# 네이버 SDK 가 고정하는 Retrofit 2.9.0 의 내장 규칙에는 아래 세 타입이 없다(2.10 부터 있다). R8 전체 모드는 keep 안 된 타입이
# 시그니처에 있으면 제네릭을 지우고, 그러면 서비스 메서드 호출 때 Retrofit 이 예외를 던진다(토큰 발급·갱신·삭제, 프로필)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# 네이버 SDK 가 끌어오는 라이브러리의 선택 의존. 런타임에 없는 것이 정상이고 쓰이는 경로가 없어 경고만 막는다
# Tink 의 정적 분석 어노테이션. 클래스·메서드에 붙어 있을 뿐 코드가 부르지 않는다
-dontwarn com.google.errorprone.annotations.**
# OkHttp 의 JVM 전용 TLS 제공자. Android 에서는 Platform 이 이 경로로 가지 않는다.
# bouncycastle 은 네이버 SDK 의 4.2.1 에는 없고 앱이 OkHttp 를 4.9 대로 올리면 참조가 생긴다
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
