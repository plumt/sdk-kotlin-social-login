package net.plumt.sociallogin.google

/** 테스트에서 설정을 짧게 만든다. 공개 API 의 [GoogleLoginConfig.builder] 를 거친다. */
internal fun googleConfig(
    serverClientId: String,
    mode: GoogleSignInMode = GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN,
    filterByAuthorizedAccounts: Boolean = false,
    autoSelectEnabled: Boolean = false,
): GoogleLoginConfig = GoogleLoginConfig.builder(serverClientId)
    .mode(mode)
    .filterByAuthorizedAccounts(filterByAuthorizedAccounts)
    .autoSelectEnabled(autoSelectEnabled)
    .build()
