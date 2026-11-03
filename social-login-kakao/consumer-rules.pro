# 카카오 SDK — 이 모듈을 의존하면 앱에 자동 적용된다.
# 출처: Kakao Developers 안드로이드 SDK ProGuard 가이드

# SDK 응답 모델은 Gson 이 필드 이름으로 읽는다. 난독화되면 전 필드가 null 이 된다
-keep class com.kakao.sdk.**.model.* { <fields>; }
-keep class * extends com.google.gson.TypeAdapter

# SDK 가 참조하는 선택적 TLS 프로바이더 (런타임에 없어도 동작)
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
