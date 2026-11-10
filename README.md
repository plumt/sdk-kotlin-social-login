# sdk-kotlin-social-login

Android 소셜 로그인(카카오·네이버·Google)을 인터페이스 하나 뒤에 묶는 Kotlin 라이브러리다.
수단마다 다른 SDK 의 콜백·오류 코드·취소 처리를 같은 결과 타입(`SocialLoginResult`)으로 접는다.
카카오·네이버·Google 의 공식 라이브러리가 아니다.

| 모듈 | 좌표 | 내용 |
|---|---|---|
| `social-login-core` | `net.plumt.sociallogin:social-login-core` | 계약·결과 모델·오류 분류·Provider 선택기. 순수 Kotlin(jar), 벤더 SDK 의존 없음 |
| `social-login-kakao` | `net.plumt.sociallogin:social-login-kakao` | 카카오 SDK v2 — 카카오톡 앱 로그인, 실패 시 카카오계정(웹) 로그인 폴백 |
| `social-login-naver` | `net.plumt.sociallogin:social-login-naver` | 네이버 로그인 SDK |
| `social-login-google` | `net.plumt.sociallogin:social-login-google` | 기본: Google Sign-In 계정 선택(투명 호스트 Activity). 선택: Credential Manager + Sign in with Google(googleid) |

## 먼저 알아 둘 것 — 클라이언트 결과는 인증이 아니다

`SocialLoginResult.Success` 는 "사용자가 소셜 로그인 화면을 통과했고 토큰을 받았다" 일 뿐이다. 앱 서버가 토큰을
검증하기 전에는 누구인지 모른다. 변조된 앱이나 다른 앱이 발급받은 토큰도 클라이언트에서는 똑같이 보인다.

| 수단 | 받는 것 | 서버가 할 검증 |
|---|---|---|
| Google | ID 토큰(JWT) | Google 공개 키로 서명, `iss`, `aud` = 웹 클라이언트 ID, `exp`, `nonce` 를 확인한다(Google 공식 라이브러리 `GoogleIdTokenVerifier` 등) |
| 카카오 | 액세스 토큰(+ OIDC 를 켰으면 ID 토큰) | `kapi.kakao.com/v1/user/access_token_info` 로 토큰을 조회하고 응답의 `app_id` 가 **내 앱 ID(숫자)** 인지 확인한다. 네이티브 앱 키와 다르다. ID 토큰이면 OIDC 규칙으로 서명·`aud`·`nonce` 를 확인한다 |
| 네이버 | 액세스 토큰 | `openapi.naver.com/v1/nid/me` 로 토큰이 유효한지 확인하고 사용자 고유 ID 를 받는다 |

- 사용자 식별은 서버가 검증한 고유 ID(`sub`·회원번호)로 한다. 이메일은 수단마다 없거나 바뀔 수 있다.
- 재전송을 막으려면 서버가 일회용 `nonce` 를 만들어 내려 주고 `SignInOptions(nonce = ...)` 로 넘긴 뒤, 받은 ID 토큰의
  `nonce` 와 대조한다(Google Credential Manager 모드·카카오 OIDC). 네이버와 Google 기본 모드는 nonce 를 담을 수 없어
  `UNSUPPORTED` 로 끝난다. 카카오에서 OIDC 를 켜지 않았으면 `CONFIGURATION` 이다(조용히 무시하지 않는다).

## 설치

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // social-login-kakao 를 쓰면 필요하다. 카카오 SDK 는 Maven Central 이 아니라 카카오 저장소에만 있다
        maven {
            url = uri("https://devrepo.kakao.com/nexus/content/groups/public/")
            content { includeGroup("com.kakao.sdk") }
        }
    }
}
```

```kotlin
// app/build.gradle.kts — 쓸 수단만 고른다. core 는 따라온다
dependencies {
    implementation("net.plumt.sociallogin:social-login-kakao:0.0.2")
    implementation("net.plumt.sociallogin:social-login-naver:0.0.2")
    implementation("net.plumt.sociallogin:social-login-google:0.1.1")
}
```

네이버 로그인 SDK(`com.navercorp.nid:oauth`)와 Credential Manager·googleid·play-services-auth 는 Maven Central·Google Maven 에
있어 저장소를 따로 더하지 않는다.

## 사용

```kotlin
val manager: SocialLoginManager<Activity> = SocialLoginManager.builder<Activity>()
    .register(KakaoLogin.provider(appContext, KakaoLoginConfig(nativeAppKey = BuildConfig.KAKAO_NATIVE_APP_KEY)))
    .register(
        NaverLogin.provider(
            appContext,
            NaverLoginConfig(
                clientId = BuildConfig.NAVER_CLIENT_ID,
                clientSecret = BuildConfig.NAVER_CLIENT_SECRET,
                clientName = getString(R.string.app_name),
            ),
        ),
    )
    .register(GoogleLogin.provider(appContext, GoogleLoginConfig(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID)))
    .build()

// 화면: 등록한 수단만 버튼으로 그린다
manager.providerIds   // [kakao, naver, google]

viewModelScope.launch {   // 회전에 살아남는 스코프로 부른다(아래 참고)
    when (val result = manager.signIn(ProviderId.GOOGLE, activity)) {
        is SocialLoginResult.Success -> api.login(ProviderId.GOOGLE.value, result.value.token)  // 서버가 검증한다
        SocialLoginResult.Cancelled -> Unit                                                    // 안내하지 않는다
        is SocialLoginResult.Failure -> showError(result.kind)                                 // kind 로 문구를 고른다
    }
}

manager.signOut(ProviderId.KAKAO)      // 이 기기의 로그인 상태만 지운다
manager.unlink(ProviderId.KAKAO)       // 앱 연결 해제(회원 탈퇴 때)
manager.refreshToken(ProviderId.NAVER) // 저장된 갱신 토큰으로 새 액세스 토큰
manager.capabilitiesOf(ProviderId.GOOGLE)  // [SIGN_OUT, UNLINK] — Google 은 토큰 갱신이 없다
```

- `SocialLoginManager` 하나를 앱에 두고 공유한다. DI 프레임워크에 의존하지 않으니 Hilt·Koin·수동 어느 쪽으로도 둔다.
  **Hilt·Dagger 로 주입받는 자리에는 `@JvmSuppressWildcards` 를 붙인다** — 타입 인자가 반공변(`SocialLoginManager<in H>`)이라
  Kotlin 이 Java 시그니처에 `? super Activity` 를 넣어, 제공 메서드의 `SocialLoginManager<Activity>` 와 키가 달라져 `MissingBinding` 이 난다.

  ```kotlin
  class LoginViewModel @Inject constructor(
      private val manager: @JvmSuppressWildcards SocialLoginManager<Activity>,
  ) : ViewModel()
  ```
- 앱 키는 런타임에 넣는다. 이 라이브러리는 어떤 키도 갖고 있지 않다. 키가 비었거나 공백뿐이면 설정 객체는 생성되고
  `isConfigured` 가 false 다 — 그 수단의 모든 호출이 SDK 를 부르지 않은 채 `CONFIGURATION` 으로 끝난다. 키를 넣지 않은
  개발·CI 빌드가 DI 생성 시점에 죽지 않는다. 화면은 `isConfigured` 로 버튼을 숨기거나 설정된 수단만 등록한다. 키를 `BuildConfig` 에 두면 APK 에 평문으로 남는다 —
  카카오 네이티브 앱 키·Google 웹 클라이언트 ID 는 원래 공개되는 값이지만, 네이버 Client Secret 은 아래 위협 모델을 본다.
- 어느 스레드에서 불러도 된다. 로그인 화면을 띄우는 SDK 호출은 Provider 가 메인 스레드로 옮긴다.
- 호출 코루틴을 취소하면 결과 없이 `CancellationException` 이 퍼진다. 이미 뜬 SDK 화면은 닫히지 않을 수 있다(SDK 가 취소를 받지 않는다).
- **`viewModelScope` 처럼 화면 회전에 살아남는 스코프로 부른다.** `lifecycleScope` 로 부르고 회전하면 코루틴만 취소되고 벤더 SDK 는
  로그인을 끝내 토큰을 저장할 수 있다. 앱은 로그아웃 상태로, SDK 는 로그인 상태로 갈라진다. 앱 전역 스코프로 부르면 벤더 SDK 가
  콜백을 주지 않을 때 잠금이 오래 남으니 `callTimeoutMillis` 를 함께 둔다.
- 오류와 실패 신호는 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

### 동시 호출

같은 `SocialLoginManager` 에서 호출이 겹치면 `ConcurrencyPolicy` 를 따른다. 로그인·로그아웃·연결 해제·갱신이 한 잠금을 함께 쓴다.

| 정책 | 동작 |
|---|---|
| `REJECT` (기본) | 진행 중이면 새 호출을 `FailureKind.BUSY` 로 바로 끝낸다. 버튼을 두 번 눌러 로그인 화면이 두 번 뜨는 것을 막는다 |
| `SERIALIZE` | 앞 호출이 끝날 때까지 기다렸다가 실행한다. 기다리다 취소되면 실행하지 않는다 |

벤더 SDK 가 콜백을 주지 않으면 그 호출이 끝나지 않아 다른 수단까지 `BUSY` 가 된다. `Builder.callTimeoutMillis(...)` 를 두면 상한을
넘은 호출을 취소하고 `FailureKind.TIMEOUT` 으로 끝내 잠금을 푼다. 계정 추가·2단계 인증 시간을 넉넉히 잡는다(예: 5분).

## 앱이 할 설정

### 카카오

1. [카카오 디벨로퍼스](https://developers.kakao.com) > 내 애플리케이션 > 플랫폼 > Android 에 패키지명과 **키 해시**를 등록한다.
   Play 스토어 배포본은 Play Console > 앱 무결성 > **앱 서명 키** 인증서로 키 해시를 만든다(업로드 키로 만들면 스토어 설치본만 실패한다).
2. 제품 설정 > 카카오 로그인 을 켠다. ID 토큰·nonce 가 필요하면 OpenID Connect 도 켠다.
3. 앱 매니페스트에 리다이렉트를 받을 Activity 를 선언한다. 스킴에 네이티브 앱 키가 들어가므로 이 라이브러리가 선언하지 않는다.
   키는 매니페스트 치환값으로 넣는다.

```kotlin
// app/build.gradle.kts — 키는 gradle.properties·환경 변수 등 저장소 밖에서 읽는다
val kakaoKey = providers.gradleProperty("KAKAO_NATIVE_APP_KEY").get()
android {
    defaultConfig {
        manifestPlaceholders["KAKAO_NATIVE_APP_KEY"] = kakaoKey
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoKey\"")
    }
}
```

```xml
<!-- app/src/main/AndroidManifest.xml -->
<activity
    android:name="com.kakao.sdk.auth.AuthCodeHandlerActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:host="oauth" android:scheme="kakao${KAKAO_NATIVE_APP_KEY}" />
    </intent-filter>
</activity>
```

`KakaoLoginConfig` 는 영숫자 키만 받는다. `YOUR_KAKAO_NATIVE_APP_KEY` 같은 자리표시자를 그대로 두면 생성 때
`IllegalArgumentException` 이 나서 설정 누락이 바로 드러난다. 빈 키는 "설정 안 됨" 으로 받는다(위 사용 참고).

카카오 SDK 는 로그아웃 요청이 실패해도 기기의 토큰을 지운다. 연결 해제는 성공했을 때만 지워, 실패하면 다시 시도할 수 있다.

### 네이버

1. [네이버 개발자 센터](https://developers.naver.com) > Application > 애플리케이션 등록 에서 "네이버 로그인" API 를 고르고,
   Android 환경에 패키지명을 등록한다. 네이버는 서명 키 해시를 받지 않는다.
2. Client ID·Client Secret 을 `NaverLoginConfig` 에 넣는다. 개발 중 상태에서는 등록한 멤버 계정만 로그인되니 출시 전에 검수를 받는다.

- 로그아웃은 기기의 토큰만 지운다. 브라우저(커스텀 탭)의 네이버 로그인 세션은 남아, 다음 로그인이 입력 화면 없이 같은 계정으로
  끝날 수 있다. 다른 네이버 계정으로 바꾸려면 사용자가 브라우저에서 네이버 로그아웃을 해야 한다.
- 네이버 SDK 는 연결 해제 요청이 서버 응답을 받기 전에 기기 토큰부터 지운다. 이 라이브러리는 망 오류처럼 다시 시도해 볼 만한
  실패(`kind.retryable`)면 지워진 기기 토큰을 되돌려, 다음 `unlink` 가 서버를 다시 부르게 한다. 그 밖의 실패는 기기 토큰이
  지워진 채다 — 연동을 확실히 끊으려면 다시 로그인한 뒤 연결 해제를 부른다.

### Google

1. Google Cloud 콘솔(또는 Firebase 콘솔 > Authentication > Google)에서 OAuth 클라이언트를 만든다.
   - **Android** 클라이언트: 패키지명과 서명 인증서 SHA-1. 배포본은 Play **앱 서명 키**의 SHA-1 이다.
   - **웹 애플리케이션** 클라이언트: 이 ID 를 `GoogleLoginConfig.serverClientId` 에 넣는다(Android 클라이언트 ID 를 넣으면 ID 토큰이 안 나온다).
2. OAuth 동의 화면의 앱 이름·지원 이메일을 채운다.
3. 화면 형태(`GoogleLoginConfig.mode`)를 고른다.

| 모드 | 경로 | nonce | 연결 해제 | 쓸 때 |
|---|---|---|---|---|
| `LEGACY_GOOGLE_SIGN_IN` (기본) | Google Sign-In 계정 선택 화면을 투명 호스트 Activity 로 띄운다 | 불가 → `UNSUPPORTED` | 지원 — `GoogleSignIn.revokeAccess` | 버튼을 눌러 로그인. 계정이 많은 기기에서도 뜬다 |
| `BUTTON` | Credential Manager 버튼 흐름(`GetSignInWithGoogleOption`), 계정 추가 가능 | 가능 | 지원(0.1.0) — `AuthorizationClient.revokeAccess` | nonce 가 필요한 버튼 로그인 |
| `BOTTOM_SHEET` | Credential Manager 계정 선택 시트(`GetGoogleIdOption`) | 가능 | 지원(0.1.0) — `BUTTON` 과 같다 | 앱 시작 때 조용한 자동 로그인(`filterByAuthorizedAccounts = true`·`autoSelectEnabled = true`) |

호출마다 모드를 바꾸려면 `SignInOptions` 에 `GoogleSignInExtension` 을 넣는다. 앱 시작 때 시트로 조용히 로그인해 보고,
`NO_CREDENTIAL` 이면 버튼에서 기본 모드로 로그인하는 흐름을 Provider 하나로 한다.

```kotlin
val silent = SignInOptions(
    nonce = null,
    extensions = listOf(GoogleSignInExtension(GoogleSignInMode.BOTTOM_SHEET, filterByAuthorizedAccounts = true, autoSelectEnabled = true)),
)
when (val result = manager.signIn(ProviderId.GOOGLE, activity, silent)) {
    is SocialLoginResult.Success -> api.login(ProviderId.GOOGLE.value, result.value.token)
    else -> showLoginButtons()  // 누르면 manager.signIn(ProviderId.GOOGLE, activity) — 기본 모드
}
```

#### 연결 해제(회원 탈퇴)

| 상황 | 부를 것 | 동작 |
|---|---|---|
| 같은 프로세스에서 이 Provider 로 로그인했다 | `manager.unlink(ProviderId.GOOGLE)` | 그 로그인 경로로 철회한다. 계정은 Provider 가 메모리에 기억한 것(저장하지 않는다) |
| 앱이 다시 떠 로그인 없이 탈퇴한다 — 기본 모드 | `manager.unlink(ProviderId.GOOGLE)` | Google Sign-In 이 마지막 계정을 안다 |
| 앱이 다시 떠 로그인 없이 탈퇴한다 — Credential Manager 모드 | `GoogleLogin.unlink(googleProvider, accountEmail)` | 인자 없는 `unlink` 는 계정을 몰라 `NOT_SIGNED_IN`(vendorCode `NoAccountToRevoke`)이다. 로그인 결과의 `SocialAccount.email` 을 앱이 보관했다가 넘긴다 |

```kotlin
// googleProvider 는 manager 에 등록한 GoogleLogin.provider(...) 인스턴스
when (val r = GoogleLogin.unlink(googleProvider, accountEmail = savedEmail)) {
    is SocialLoginResult.Success -> api.deleteAccount()
    SocialLoginResult.Cancelled -> Unit
    is SocialLoginResult.Failure -> if (r.kind.retryable) showRetry() else showError(r.kind)
}
```

- 화면을 띄우지 않는다. Google 서버에서 이 앱(같은 OAuth 프로젝트)에 준 권한을 모두 철회하고 기기 로그인 상태를 지운다.
  다음 로그인은 계정 선택과 동의 화면을 다시 거친다.
- 망 실패(`NETWORK`, 재시도 가능)면 기기 상태와 기억한 계정이 남아 같은 호출로 다시 시도한다.
- `GoogleLogin.unlink` 는 `SocialLoginManager` 의 동시 호출 잠금을 거치지 않는다. 같은 Provider 의 다른 호출과 겹치지 않게 부른다.
- 앱 서버가 Google 의 갱신 토큰을 따로 받아 둔 경우(오프라인 접근)는 이 라이브러리가 다루지 않는다. 그 토큰은 서버가 철회한다.

| Android | 로그인(Credential Manager 모드) | 로그인(기본 모드) | 연결 해제 |
|---|---|---|---|
| 6–13 (API 23–33) | Google Play 서비스(`credentials-play-services-auth`) | Google Play 서비스 | Google Play 서비스(`revokeAccess`) |
| 14 이상 (API 34+) | 시스템 Credential Manager → Google Play 서비스 | Google Play 서비스 | Google Play 서비스(`revokeAccess`) |

연결 해제는 API 레벨이 아니라 기기의 Google Play 서비스 버전에 달렸다. Play 서비스가 없거나 오래됐으면 `UNSUPPORTED` 다.

- 기본 모드는 Google 이 지원 중단(deprecated)으로 표시한 `play-services-auth` 의 `GoogleSignIn` 을 쓴다. 지금은 동작하지만
  Google 이 제거하면 이 라이브러리도 모드를 바꿔야 한다. Credential Manager 를 거치지 않고 ID 토큰을 받는 지원 중인 API 는 없다.
- 기본 모드의 호스트 Activity 는 라이브러리 매니페스트가 선언한다(내보내지 않음). 앱은 결과 전달 코드를 쓰지 않는다.
  회전해도 계정 선택 화면이 다시 뜨지 않고, 화면 자체에는 시간 제한이 없다(호스트가 10초 안에 뜨지 않으면 `INTERRUPTED`).
- Credential Manager 시트는 사용자가 여러 번 닫으면 Google 이 한동안 시트 표시를 막을 수 있다(쿨다운, 기기·OS 에 따라 다르다).
  그동안 화면 없이 `Cancelled` 가 와 버튼이 무반응처럼 보인다. 버튼 탭 로그인에는 시트를 쓰지 않는다.

#### 알려진 문제 — 계정이 많은 기기의 `TransactionTooLargeException`

Google 계정이 여럿이고 화면 밀도가 높은 일부 기기에서, Credential Manager 가 시스템 계정 선택 화면으로 넘기는
Binder 데이터가 한도(약 1MB)를 넘어 계정 선택 시트가 뜨지 못하는 사례가 있다. 앱이 만드는 요청은 작아도 응답 경로가
커질 수 있어 앱 쪽에서 크기를 줄일 수 없다. 그래서 0.0.2 부터 이 경로를 거치지 않는 Google Sign-In 을 기본으로 둔다.

- Credential Manager 모드에서 `BOTTOM_SHEET` 로그인이 이 예외(원인 사슬에 있거나 문구로만 옮겨 온 경우 포함)로 실패하면
  버튼 흐름으로 한 번만 자동 재시도한다. nonce 는 그대로 쓴다.
- 어느 모드든 이 예외로 끝나면 `FailureKind.UNSUPPORTED` + `vendorCode = "TransactionTooLargeException"` 이다. 앞 시도의 예외는
  `cause.suppressed` 에 남는다. Credential Manager 모드면 기본 모드로 바꾸고, 그래도 안 되면 다른 로그인 수단을 안내한다.
- 다른 프로세스(시스템 선택 앱·Google Play 서비스)에서 난 예외는 앱에 이 이름 없이 올 수 있다. 그때는 판별되지 않는다.

#### 알려진 한계 — Credential Manager 모드의 연결 해제(0.1.0)

- `AuthorizationClient.revokeAccess` 는 `play-services-auth` 21.4.0 에 처음 들어왔고 기기의 Google Play 서비스도 이 기능을
  알아야 한다. 오래된 Play 서비스면 `UNSUPPORTED`(vendorCode `UnsupportedApiCallException` 또는 `ApiException(17)`)이다.
- 그 계정이 이 앱에 동의한 이력이 없으면 철회할 것이 없어 기기 상태만 지우고 `Success` 를 돌려준다(0.1.1). Google 계정의
  연결된 앱 목록에 무엇이 남는지는 Google 정책에 따른다.
- 철회할 계정은 이메일로 지정한다. 기기에서 그 계정이 지워졌으면 Google 이 거절할 수 있다(`ApiException` 상태 코드는 확인하지
  못했다 — 표에 없는 코드는 `UNKNOWN` 에 vendorCode 로 남는다). 그때는 사용자가 Google 계정 설정의
  "서드 파티 앱 및 서비스" 에서 끊거나, 앱 서버가 처리한다.
- `play-services-auth` 22.0.0 은 Google Sign-In API 를 지웠고 minSdk 가 24 다. 기본 모드가 그 API 를 쓰므로 이 모듈은 21.x
  (21.6.0)에 머문다. 앱이 22 이상을 고르면 기본 모드 로그인이 `NoClassDefFoundError` 로 깨진다 — 21.x 를 유지한다.

## 위협 모델

| 위협 | 대응 |
|---|---|
| 클라이언트가 보낸 토큰 위조·다른 앱 토큰 재사용 | 서버 검증(위 표). 이 라이브러리는 검증하지 않는다 |
| ID 토큰 재전송 | 서버가 만든 일회용 `nonce` 를 넘기고 서버가 대조한다 |
| 로그·크래시 리포트로 토큰·이메일 유출 | 라이브러리는 아무것도 로그로 남기지 않는다. `SocialToken`·`SocialAccount`·`SignInOptions`·각 `*Config` 의 `toString` 은 토큰·이메일·이름·키를 가린다. 실패 메시지에는 벤더 SDK 문구(서버 응답이 섞일 수 있다)를 넣지 않고 코드만 넣는다. 값을 직접 꺼내 찍는 것은 막지 못한다 |
| 설정 값이 예외 메시지로 유출 | 생성 검사 실패 메시지에 키·Secret·nonce 값 대신 길이만 넣는다 |
| APK 안의 네이버 Client Secret | 네이버 SDK 구조상 앱에 들어간다. 디컴파일로 읽힐 수 있다고 보고, 서버는 토큰을 반드시 네이버 API 로 검증하며, 새면 재발급한다 |
| 토큰 저장 | 이 라이브러리는 토큰을 저장하지 않는다. 카카오·네이버 SDK 가 자기 저장소에 둔다(갱신·로그아웃에 쓴다). 앱 세션 토큰 저장은 앱 몫이다 |

## 확장

| 확장 지점 | 하는 일 |
|---|---|
| `SocialLoginProvider<H>` | 새 수단(Apple·Facebook·LINE 등)을 붙인다. 모듈 하나에서 구현하고 `ProviderId("apple")` 처럼 새 식별자를 쓴다. core 를 고치지 않는다 |
| `capabilities` | 수단이 지원하는 동작(`SIGN_OUT`·`UNLINK`·`REFRESH_TOKEN`)을 선언한다. 선언하지 않은 동작은 Manager 가 부르지 않는다 |
| `SocialLoginManager<H>` | 인터페이스다. 로그 적재·계정 병합 같은 정책을 데코레이터로 감싸 끼운다 |
| `ConcurrencyPolicy` | 동시 호출을 거절할지 줄 세울지 고른다 |
| `SocialLoginErrors` | 새 Provider 가 벤더 고유 오류를 분류하고 남은 예외를 같은 규칙(망 → `NETWORK`)으로 접는다 |
| 호스트 타입 `H` | core 는 Android 에 의존하지 않는다. Android Provider 는 `Activity` 를 받는다 |

```kotlin
class AppleLoginProvider(/* ... */) : SocialLoginProvider<Activity> {
    override val id = ProviderId("apple")
    override val capabilities = setOf(Capability.SIGN_OUT)
    override suspend fun signIn(host: Activity, options: SignInOptions): SocialLoginResult<SocialAccount> = try {
        val idToken = /* 벤더 SDK 호출 */
        SocialLoginResult.Success(SocialAccount(id, SocialToken(accessToken = null, idToken = idToken)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SocialLoginErrors.failureOf(e, message = "Apple 로그인 실패: ${e.javaClass.name}", providerId = id)
    }
    override suspend fun signOut(): SocialLoginResult<Unit> = /* ... */
}
```

## 브랜드 로그인 버튼

Compose 버튼 모듈은 두지 않는다. 각 수단의 로고·버튼은 상표이고, 카카오·네이버·Google 이 각자 디자인 가이드와 공식
리소스를 배포하며 쓰임새(크기·색·문구)를 정해 둔다. 그 리소스를 Apache-2.0 라이브러리에 다시 담아 배포할 권리가
분명하지 않고, 가이드가 바뀌면 라이브러리가 뒤처진다. 앱은 각 디자인 가이드의 공식 리소스로 버튼을 그리고,
눌렀을 때 `manager.signIn(...)` 을 부른다.

## 최소 요구사항

- Android minSdk 23 — 카카오 SDK v2 가 23 을 요구한다. 네이버 SDK·Credential Manager·Google Sign-In 은 21 이지만 수단을 섞어 쓰는 앱을 위해 모듈을 23 으로 맞췄다
- compileSdk 36, Java 11 바이트코드, Kotlin 2.2
- `social-login-core` 는 순수 JVM 이라 서버·테스트에서도 쓴다
- 의존(검증한 버전): 카카오 `com.kakao.sdk:v2-user` 2.20.6, 네이버 `com.navercorp.nid:oauth` 5.10.0, `androidx.credentials` 1.5.0,
  `com.google.android.libraries.identity.googleid:googleid` 1.1.1, `com.google.android.gms:play-services-auth` 21.6.0(Credential Manager 모드의 연결 해제에 21.4.0 이상 필요, 22.0.0 은 쓰지 않는다),
  `kotlinx-coroutines` 1.10.2. 벤더 SDK 타입은 공개 API 에 드러내지 않는다(`implementation`). 앱이 SDK 를 직접 쓰려면 직접 의존한다
- 앱이 벤더 SDK 를 더 높은 버전으로 올려도 오류 분류는 원인 **이름**으로 해서, 이 라이브러리가 모르는 원인은 예외 없이 `UNKNOWN`
  (네이버는 HTTP 상태 분류)으로 끝난다. 네이버 연결 해제 실패 뒤의 토큰 되돌리기는 SDK 내부 저장소에 기대므로 5.10.0 이 아닌
  버전에서는 되지 않을 수 있다(그때는 실패 메시지가 알린다)
- ProGuard·R8 규칙은 모듈과 함께 따라간다([R8·난독화](#r8난독화))

## R8·난독화

앱에 이 라이브러리용 keep 규칙을 더하지 않아도 된다. 규칙은 각 모듈의 AAR(`consumer-rules.pro`)과 `social-login-core` jar(`META-INF/proguard`)에 실린다.

`./gradlew build` 는 발행하지 않는 `:sample` 앱을 수단 조합 7가지(카카오·네이버·Google 단독, 둘씩, 전부)로 R8 전체 모드 빌드하고, dex·mapping 에서 Retrofit 서비스 메서드의 제네릭 시그니처, Gson `TypeAdapter` 생성자, `SignInOptions.Extension` 구분을 확인한다. 기기 없이 도는 정적 검사라 실제 로그인 동작을 확인하지는 않는다.

공개 0.0.1 의 알려진 문제와 앱에서 피하는 방법은 다음과 같다. 0.0.2 에서는 필요 없다.

| 대상 | 증상 | 앱 규칙에 넣을 것 |
|---|---|---|
| 네이버 | release 빌드가 `Missing class`(`errorprone`·`conscrypt`·`openjsse`)로 실패 | 아래 `-dontwarn` |
| 카카오·네이버 | release 에서 호출 때 Retrofit 예외(`Call return type must be parameterized`, `ClassCastException`) | 아래 `-keep` |

```
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
```

`retrofit` 을 앱에서 2.10 이상으로 올려도 호출 문제는 사라지지만, 카카오·네이버 SDK 는 2.9.0 을 고정하고 있어 이 라이브러리는 버전을 올려 강제하지 않는다.

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `social-login-core-v0.0.1`)이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정하고 CI 가 대조한다.
라인 커버리지 90% 미만이면 `koverVerify` 가 빌드를 막는다.

## 라이선스

[Apache License 2.0](LICENSE)

카카오·네이버·Google 은 각 회사의 상표다. 이 라이브러리는 각 회사와 관계가 없다.
