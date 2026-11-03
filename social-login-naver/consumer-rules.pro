# 네이버 로그인 SDK(NidOAuth) — 이 모듈을 의존하면 앱에 자동 적용된다.

# SDK 가 리플렉션·직렬화로 콜백·응답 타입을 다루므로 공개 API 를 보존한다
-keep public class com.navercorp.nid.** { *; }
-dontwarn com.navercorp.nid.**
