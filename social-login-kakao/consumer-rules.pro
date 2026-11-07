# 카카오 SDK — 이 모듈을 의존하면 앱에 자동 적용된다.
# 출처: Kakao Developers 안드로이드 SDK ProGuard 가이드

# SDK 응답 모델은 Gson 이 필드 이름으로 읽는다. 난독화되면 전 필드가 null 이 된다
-keep class com.kakao.sdk.**.model.* { <fields>; }
# @JsonAdapter 가 어댑터를 리플렉션으로 만들므로 인자 없는 생성자까지 남긴다(멤버 없는 keep 은 전체 모드에서 생성자를 지운다)
-keep class * extends com.google.gson.TypeAdapter { <init>(); }

# SDK 가 참조하는 선택적 TLS 프로바이더 (런타임에 없어도 동작)
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# 카카오 SDK 가 고정하는 Retrofit 2.9.0 의 내장 규칙에는 아래 세 타입이 없다(2.10 부터 있다). R8 전체 모드는 keep 안 된 타입이
# 시그니처에 있으면 제네릭을 지우고, 그러면 서비스 메서드 호출 때 Retrofit 이 예외를 던진다(토큰 발급·갱신·로그아웃·연결 해제)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
