# Google 로그인 — 이 모듈을 의존하면 앱에 자동 적용된다.

# Credential Manager 모드. 출처: Android 개발자 문서 Credential Manager 통합 가이드의 ProGuard 규칙
# Google Play 서비스 자격 증명 제공자를 리플렉션으로 찾는다
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}

# 기본 모드(Google Sign-In). 결과 계정·옵션이 Parcelable 로 프로세스를 오간다
-keep class com.google.android.gms.auth.api.signin.** { *; }
