package net.plumt.sociallogin.kakao

/** 테스트에서 설정을 짧게 만든다. 공개 API 의 [KakaoLoginConfig.builder] 를 거친다. */
internal fun kakaoConfig(nativeAppKey: String, useKakaoTalk: Boolean = true): KakaoLoginConfig =
    KakaoLoginConfig.builder(nativeAppKey).useKakaoTalk(useKakaoTalk).build()
