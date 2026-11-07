# social-login-core — 이 모듈을 의존하면 앱에 자동 적용된다.

# SignInOptions 는 Extension 을 클래스로 구분한다(같은 클래스 거절, extension(Class) 조회). R8 이 모양이 같은 구현을 한 클래스로
# 합치면 서로 다른 Extension 이 release 에서만 "같은 클래스" 예외로 끝나므로 합치지 못하게 한다. 이름·제거는 R8 에 맡긴다
-keep,allowobfuscation,allowshrinking class * implements net.plumt.sociallogin.SignInOptions$Extension
