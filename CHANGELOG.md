# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## social-login-core

### [Unreleased]

### [0.0.2] - 2026-11-07

#### 고침
- R8 이 모양이 같은 `SignInOptions.Extension` 구현 둘을 한 클래스로 합치면 서로 다른 Extension 이 release 에서만 "같은 클래스" 검사에 걸릴 수 있었다. jar 의 `META-INF/proguard/social-login-core.pro` 가 합치기를 막는다. 0.0.1 에는 `extensions` 가 없어 해당 없다

#### 추가
- `SocialLoginManager.Builder.callTimeoutMillis` — Provider 호출 시간 상한. 넘으면 Provider 코루틴을 취소하고 새 갈래 `FailureKind.TIMEOUT`(재시도 가능)으로 끝내 잠금을 푼다. 벤더 SDK 가 콜백을 주지 않을 때 모든 호출이 `BUSY` 로 묶이던 문제를 막는다. 기본은 상한 없음(0.0.1 동작)
- `SignInOptions(nonce, extensions)` 와 `SignInOptions.Extension` — 수단별 추가 선택 사항을 호출마다 넘긴다(예: Google 화면 모드). 0.0.1 생성자 `SignInOptions()`·`SignInOptions(nonce)` 는 그대로다

#### 호환성
- 바이너리 호환. `.api` 는 추가만 있다
- `FailureKind` 에 `TIMEOUT` 이 늘었다. `when` 에 `else` 를 두지 않은 소비 코드(KDoc 은 `else` 를 권한다)는 다시 컴파일할 때 분기를 더해야 한다(이미 빌드된 앱은 영향 없음)

### [0.0.1] - 발행

#### 추가
- `SocialLoginProvider<H>` 계약 — 로그인과 선택 동작(`Capability`: 로그아웃·연결 해제·토큰 갱신). 새 수단은 이 인터페이스를 구현한 모듈로 붙인다
- `ProviderId` 값 객체(`KAKAO`·`NAVER`·`GOOGLE` 상수, 새 식별자 허용)
- `SocialAccount`·`SocialToken`·`SignInOptions`(nonce) — `toString` 이 토큰·이메일·이름·nonce 를 가린다
- `SocialLoginResult` = `Success` / `Cancelled` / `Failure(kind, message, providerId, vendorCode, cause)`, `FailureKind`(재시도 가능 여부 포함)
- `SocialLoginErrors` — 수단과 무관한 예외 분류(원인 사슬의 망 예외 → `NETWORK`)
- `SocialLoginManager` — 등록 순서 보존, 중복 등록 거절, 미등록·미지원 동작은 Provider 를 부르지 않는 실패, Provider 예외 감싸기, 동시 호출 정책 `ConcurrencyPolicy`(`REJECT` 기본·`SERIALIZE`)

## social-login-kakao

### [Unreleased]

### [0.0.2] - 2026-11-07

#### 바뀜 (동작 계약)
- **동의 화면에서 "취소"를 누르면 `Cancelled` 다.** 로그인 중 `AuthError(AccessDenied)` 를 사용자 취소로 본다. 0.0.1 은 카카오톡에서 취소하면 웹 로그인 화면을 한 번 더 띄우고, 웹에서 취소하면 `Failure(ACCESS_DENIED)` 였다
- **빈 `nativeAppKey` 는 생성 때 던지지 않는다.** 비었거나 공백뿐이면 "설정 안 됨"(`KakaoLoginConfig.isConfigured` = false)이고, 모든 호출이 SDK 를 부르지 않은 채 `CONFIGURATION` 으로 끝난다. 0.0.1 은 `IllegalArgumentException` 이었다 — 키가 없는 개발·CI 빌드가 DI 생성 시점에 죽었다. 비어 있지 않은 틀린 키(자리표시자 등)는 여전히 `IllegalArgumentException` 이다
- **nonce 를 넘겼는데 ID 토큰이 없으면(OIDC 미활성화) `CONFIGURATION`** 이다. 0.0.1 은 ID 토큰 없이 `Success` 를 돌려줘 서버의 nonce 검증이 엉뚱한 곳에서 실패했다

#### 고침
- 오류 원인을 열거 상수가 아니라 이름으로 분류한다. 앱이 더 새 카카오 SDK 를 골라 이 모듈이 모르는 원인이 와도 `NoWhenBranchMatchedException`·`NoSuchFieldError` 없이 `UNKNOWN` 으로 끝나고, vendorCode 에 원인 이름이 남는다

- **R8 전체 모드에서 카카오 API 호출이 실패하던 문제(공개 0.0.1 에 있음).** 카카오 SDK 가 고정하는 Retrofit 2.9.0 의 내장 규칙이 부족해 서비스 메서드 6개(토큰 발급·갱신, 로그아웃, 연결 해제, 토큰 확인, agt)의 제네릭 시그니처가 지워지고, 호출 때 `Call return type must be parameterized` 예외가 났다. 소비자 규칙으로 고쳤고 의존 버전은 그대로다. 0.0.1 에 머문다면 [README](README.md#r8난독화)의 규칙을 앱에 넣는다
- `@JsonAdapter` 가 만드는 Gson `TypeAdapter` 의 생성자가 R8 에 지워지던 것을 규칙에 `<init>()` 를 적어 막았다. 생성자가 없어도 Gson 이 다른 경로로 만들어 실패 사례를 재현하지는 못했다

#### 추가
- `KakaoLoginConfig.isConfigured`

### [0.0.1] - 발행

#### 추가
- `KakaoLogin.provider` · `KakaoLoginConfig` — 카카오톡 앱 로그인, 사용자 취소가 아니면 카카오계정 로그인 폴백, nonce(OIDC)
- 로그아웃·연결 해제·토큰 갱신
- `ClientError`·`AuthError`·`ApiError` 의 모든 원인을 `FailureKind` 로 분류
- 프로세스 전역 SDK 초기화를 첫 호출 때 한 번만(`Application` 수정 불필요), 다른 키로의 재초기화는 `CONFIGURATION`

## social-login-naver

### [Unreleased]

### [0.0.2] - 2026-11-07

#### 바뀜 (동작 계약)
- **동의 화면에서 "취소"를 누르면 `Cancelled` 다.** 로그인 중 `SERVER_ERROR_ACCESS_DENIED` 를 사용자 취소로 본다. 0.0.1 은 `Failure(ACCESS_DENIED)` 였다
- **빈 `clientId`·`clientSecret`·`clientName` 은 생성 때 던지지 않는다.** 하나라도 비었거나 공백뿐이면 "설정 안 됨"(`NaverLoginConfig.isConfigured` = false)이고, 모든 호출이 SDK 를 부르지 않은 채 `CONFIGURATION` 으로 끝난다. 0.0.1 은 `IllegalArgumentException` 이었다. 비어 있지 않은 틀린 값은 여전히 `IllegalArgumentException` 이다

#### 고침
- 연결 해제가 망 오류 등 재시도해 볼 만한 이유로 실패하면 지워진 기기 토큰을 되돌린다. 네이버 SDK 는 서버 응답을 보기 전에 기기 토큰부터 지워, 0.0.1 에서는 재시도가 `NOT_SIGNED_IN` 으로 끝나고 네이버 서버의 연동이 영구히 남았다. 재시도해도 소용없는 실패는 SDK 동작대로 기기 토큰이 지워진 채다
- HTTP 4xx 로 `onFailure` 가 올 때 SDK 가 오류 코드를 갱신하지 않아, 기기에 남은 지난 코드(연결 오류·사용자 취소)로 잘못 분류되던 문제. 코드가 그대로면 HTTP 상태로 분류한다(401 → `AUTHENTICATION`)
- 오류 코드를 열거 상수가 아니라 이름으로 분류한다. 모르는 코드는 예외 없이 HTTP 상태로 분류된다

- **네이버를 쓰는 앱의 R8 release 빌드가 실패하던 문제(공개 0.0.1 에 있음).** 네이버 SDK 가 끌어오는 Tink·OkHttp 의 선택 의존이 `Missing class` 로 잡혔다(네이버 단독, 네이버+카카오, 네이버+Google). 런타임에 쓰이지 않는 어노테이션·JVM 전용 TLS 제공자만 소비자 규칙의 `-dontwarn` 으로 막았다
- **R8 전체 모드에서 네이버 API 호출이 실패하던 문제(공개 0.0.1 에 있음).** 네이버 SDK 가 고정하는 Retrofit 2.9.0 의 내장 규칙이 부족해 서비스 메서드 6개(토큰 발급·갱신·삭제, 프로필 등)의 제네릭 시그니처가 지워지고, 호출 때 `ClassCastException` 이 났다. 소비자 규칙으로 고쳤고 의존 버전은 그대로다. 0.0.1 에 머문다면 [README](README.md#r8난독화)의 규칙을 앱에 넣는다

#### 추가
- `NaverLoginConfig.isConfigured`

### [0.0.1] - 발행

#### 추가
- `NaverLogin.provider` · `NaverLoginConfig` — 네이버 로그인, 로그아웃(기기 토큰), 연결 해제(서버 토큰 삭제), 토큰 갱신
- 콜백 문구가 아니라 SDK 오류 코드(`NidOAuthErrorCode`)로 분류. nonce 요청은 `UNSUPPORTED`

## social-login-google

### [Unreleased]

### [0.1.0] - 2026-11-08

#### 추가
- **Credential Manager 모드(`BUTTON`·`BOTTOM_SHEET`)의 연결 해제.** `AuthorizationClient.revokeAccess` 로 Google 서버에서 이 앱에 준 권한을 철회하고 기기 로그인 상태를 지운다. 화면을 띄우지 않는다. 계정은 그 Provider 인스턴스의 마지막 로그인에서 메모리로 기억한다(기기에 저장하지 않는다)
- `GoogleLogin.unlink(provider, accountEmail)` — 프로세스가 다시 떠 마지막 로그인을 모를 때 로그인 때 받은 이메일로 철회한다. `IllegalArgumentException`: `GoogleLogin.provider` 로 만들지 않은 Provider·공백뿐인 이메일
- 실패 신호 `NOT_SIGNED_IN`(vendorCode `NoAccountToRevoke`) — 철회할 계정을 모를 때. Google 을 부르지 않는다
- 오류 분류: `UnsupportedApiCallException`(기기의 Play 서비스가 기능을 모름)·`ApiException(17)` → `UNSUPPORTED`

#### 바뀜
- **`capabilities` 가 모든 모드에서 `[SIGN_OUT, UNLINK]` 다.** 0.0.2 의 Credential Manager 모드는 `[SIGN_OUT]` 이라 `SocialLoginManager.unlink` 가 Provider 를 부르지 않고 `UNSUPPORTED` 였고, Provider 의 `unlink()` 도 `UNSUPPORTED` 였다. 이제 철회하거나, 계정을 모르면 `NOT_SIGNED_IN` 이다
- 연결 해제의 주 경로는 그 인스턴스의 마지막 로그인 경로(호출별 `GoogleSignInExtension` 포함), 모르면 설정 모드다. 기본 모드에서도 계정 이메일을 알면 `AuthorizationClient.revokeAccess` 를 보조로 한 번 더 부른다(실패는 결과에 넣지 않는다)
- 의존 `com.google.android.gms:play-services-auth` 21.3.0 → 21.6.0(`revokeAccess` 는 21.4.0 부터). 22.0.0 은 Google Sign-In API 를 지우고 minSdk 24 라 쓰지 않는다

#### 호환성
- 바이너리 호환. `.api` 는 `GoogleLogin.unlink(SocialLoginProvider, String)` 한 줄 추가만 있다. 기본 모드(`LEGACY_GOOGLE_SIGN_IN`)·기존 시그니처는 그대로다
- 동작 계약이 바뀐 곳은 위 "바뀜" 의 Credential Manager 모드 연결 해제 하나다. `UNSUPPORTED` 를 보고 서버 철회로 넘기던 앱은 이제 `Success`·`NOT_SIGNED_IN` 을 받는다

### [0.0.2] - 2026-11-07

#### 바뀜 (동작 계약)
- **기본 모드가 `GoogleSignInMode.LEGACY_GOOGLE_SIGN_IN` 으로 바뀌었다.** Google Sign-In(`play-services-auth`) 계정 선택 화면을 투명 호스트 Activity 로 띄운다. 계정이 많은 고밀도 기기에서 Credential Manager 시트가 Binder 전송 한도(`TransactionTooLargeException`)에 걸려 뜨지 못한 사례가 있어, 실기기에서 문제가 없던 이 경로를 기본으로 삼았다. Credential Manager 모드(`BOTTOM_SHEET`·`BUTTON`)는 그대로 고를 수 있다
  - 기본 모드는 nonce 를 담을 수 없다. 기본 설정으로 `SignInOptions(nonce = ...)` 를 넘기면 `UNSUPPORTED` 다. nonce 가 필요하면 `mode = BUTTON`(또는 `BOTTOM_SHEET`)을 명시한다
  - 기본 모드는 `capabilities` 에 `UNLINK` 가 있다 — 연결 해제는 Google 서버의 권한 철회(`revokeAccess`)다. Credential Manager 모드는 0.0.1 과 같다(`SIGN_OUT` 만)
  - 지원 중단 표시가 붙은 Google API 를 쓴다. 의존에 `com.google.android.gms:play-services-auth` 21.3.0 이 늘었고 라이브러리 매니페스트가 호스트 Activity 를 선언한다
- **빈 `serverClientId` 는 생성 때 던지지 않는다.** "설정 안 됨"(`GoogleLoginConfig.isConfigured` = false)이고 모든 호출이 `CONFIGURATION` 이다. 0.0.1 은 `IllegalArgumentException` 이었다
- 로그아웃은 설정한 모드의 상태를 지우고(그 실패만 결과로 알림), 다른 경로의 상태도 보조로 지운다

#### 추가
- `GoogleSignInExtension` — 호출마다 화면 모드를 고른다(`SignInOptions` 의 `extensions`). 조용한 시트 자동 로그인과 버튼 로그인을 Provider 하나로 한다
- Google Sign-In 상태 코드 분류(`ApiException(<코드>)`): 12501 → `Cancelled`, 10 → `CONFIGURATION`, 7·15 → `NETWORK`, 4 → `NOT_SIGNED_IN`, 12502 → `BUSY`, Play 서비스 없음·갱신 필요 → `UNSUPPORTED`. 실패가 `RESULT_CANCELED` 로 와도 결과 데이터를 먼저 해석해 설정 오류를 취소로 묻지 않는다
- 어느 모드든 `TransactionTooLargeException`(원인 사슬, 또는 문구로만 옮겨 온 경우)은 `UNSUPPORTED` + vendorCode `TransactionTooLargeException` 이다
- `GoogleLoginConfig.isConfigured`
- `NoCredentialException` 실패 메시지에 설정(웹 클라이언트 ID·SHA-1) 확인 안내

#### 호환성
- 바이너리 호환. `.api` 는 추가만 있다. `GoogleSignInMode` 에 상수가 늘어 `else` 없는 `when` 은 다시 컴파일할 때 분기를 더해야 한다
- 기본값이 바뀐 것은 계약 변경이다. 0.0.1 의 Credential Manager 동작을 유지하려면 `GoogleLoginConfig(serverClientId, mode = GoogleSignInMode.BOTTOM_SHEET)` 처럼 모드를 명시한다

### [0.0.1] - 발행

#### 추가
- `GoogleLogin.provider` · `GoogleLoginConfig` · `GoogleSignInMode` — Credential Manager 의 계정 선택 시트(`GetGoogleIdOption`)와 버튼 흐름(`GetSignInWithGoogleOption`), nonce
- 로그아웃(자격 증명 상태 지우기). 연결 해제·갱신은 `UNSUPPORTED`
- Credential Manager 예외를 `FailureKind` 로 분류
- 계정이 많은 기기의 `TransactionTooLargeException` 완화 — 시트 실패 시 버튼 흐름으로 1회 자동 재시도, 그래도 실패하면 `UNSUPPORTED` + vendorCode `TransactionTooLargeException`
