# Credential Manager — 이 모듈을 의존하면 앱에 자동 적용된다.
# 출처: Android 개발자 문서 Credential Manager 통합 가이드의 ProGuard 규칙

# Google Play 서비스 자격 증명 제공자를 리플렉션으로 찾는다
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
