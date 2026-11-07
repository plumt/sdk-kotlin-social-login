# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 모듈별로 적는다.

공통 규칙:

- **틀린 인자 → `IllegalArgumentException`.** 생성자·설정 객체가 즉시 던진다. 메시지에 허용 범위를 넣고, 받은 값은
  비밀이 아닐 때만 넣는다(키·Secret·토큰·nonce·이메일은 길이만).
- **빈 키는 틀린 인자가 아니라 "설정 안 됨" 이다(0.0.2).** 각 `*LoginConfig` 는 비었거나 공백뿐인 키를 받아 `isConfigured = false` 로 두고,
  그 수단의 모든 호출이 SDK 를 부르지 않은 채 `CONFIGURATION` 으로 끝난다.
- **그 밖의 실패는 예외가 아니라 `SocialLoginResult`** 다. `Success` / `Cancelled`(사용자가 닫음) / `Failure(kind, message, providerId, vendorCode, cause)`.
  벤더 SDK 의 원래 예외는 `cause`, 원래 코드는 `vendorCode` 에 남는다. 분기는 `kind` 로 하고 `message` 문구에 기대지 않는다.
- **코루틴 취소**(`CancellationException`)는 결과로 바꾸지 않고 그대로 퍼진다. 사용자 취소(`Cancelled`)와 다르다.
- `Error`(OutOfMemoryError 등)는 잡지 않는다.

## social-login-core

### 예외

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `ProviderId` 가 영소문자로 시작하는 1..32 자 `[a-z0-9_-]` 가 아닐 때 | 식별자를 바로잡는다 |
| `IllegalArgumentException` | `SocialToken` 의 `accessToken`·`idToken` 이 둘 다 null, 토큰이 공백뿐, 만료 시각이 음수, `scopes` 에 공백 원소가 있을 때 | Provider 구현이 "없음" 을 null 로 넣는다 |
| `IllegalArgumentException` | `SocialAccount` 의 `email`·`displayName`·`profileImageUrl` 이 공백뿐일 때 | "없음" 은 null 로 넣는다 |
| `IllegalArgumentException` | `SignInOptions.nonce` 가 빈 문자열이거나 512 자를 넘거나 공백 문자를 포함할 때 | 서버가 만든 nonce 를 그대로 넣는다 |
| `IllegalArgumentException` | `SignInOptions.extensions` 에 같은 클래스가 둘 이상일 때. 서로 다른 구현이 R8 병합으로 걸리지 않도록 jar 가 규칙을 싣는다(0.0.2) | 수단별 확장은 종류마다 하나만 넣는다 |
| `IllegalArgumentException` | `SocialLoginManager.Builder.callTimeoutMillis` 가 0 이하일 때 | 1 이상을 넣거나 부르지 않는다(상한 없음) |
| `IllegalArgumentException` | `SocialLoginResult.Failure` 의 `message` 가 공백뿐이거나 `vendorCode` 가 공백뿐일 때 | Provider 구현을 고친다 |
| `IllegalArgumentException` | `SocialLoginManager.Builder.register` 에 같은 `ProviderId` 를 두 번 넣을 때(나중 것이 조용히 덮지 않는다) | 수단마다 Provider 하나만 등록한다 |
| `IllegalArgumentException` | `SocialLoginErrors.classify`·`failureOf` 에 `CancellationException` 을 넣을 때 | 취소는 분류하지 않고 다시 던진다 |

### 실패 갈래 (`FailureKind`)

| 갈래 | 재시도 | 뜻 | 호출부가 할 일 |
|---|---|---|---|
| `NETWORK` | 예 | 망 연결 실패·시간 초과(원인 사슬 16 단계 안의 `IOException`·`TimeoutException`) | 연결 안내 후 재시도 |
| `PROVIDER_SERVER` | 예 | 소셜 수단 서버 오류·점검·호출 한도 | 잠시 뒤 재시도 |
| `INTERRUPTED` | 예 | 다른 화면·시스템 이벤트에 끊김 | 다시 시도 |
| `BUSY` | 예 | 같은 Manager 에서 다른 호출이 진행 중(`ConcurrencyPolicy.REJECT`) | 무시하거나 앞 호출이 끝난 뒤 다시 |
| `CONFIGURATION` | 아니오 | 앱 키·패키지명·키 해시·SHA·리다이렉트 스킴·SDK 초기화 등 설정 오류 | 개발자가 콘솔·앱 설정을 고친다 |
| `AUTHENTICATION` | 아니오 | 토큰 만료·폐기, 인가 코드 오류 | 다시 로그인 |
| `ACCESS_DENIED` | 아니오 | 로그인 밖의 동작에서 동의 부족, 연령·계정 제한(로그인 동의 화면의 "취소"는 `Cancelled`) | 안내 |
| `NOT_SIGNED_IN` | 아니오 | 로그인한 적이 없어 로그아웃·연결 해제·갱신할 토큰이 없음 | 로그인부터 |
| `NO_CREDENTIAL` | 아니오 | 기기에 쓸 계정이 없음(Google 시트) | 버튼 흐름(`GoogleSignInMode.BUTTON`)으로 계정 추가를 연다 |
| `PROVIDER_NOT_REGISTERED` | 아니오 | Manager 에 등록하지 않은 수단 | 등록한 수단(`providerIds`)만 버튼으로 그린다 |
| `UNSUPPORTED` | 아니오 | Provider 가 선언하지 않은 동작, 지원하지 않는 옵션(네이버 nonce), 기기·앱 환경 미지원 | `capabilitiesOf` 로 미리 확인한다 |
| `UNKNOWN` | 아니오 | 그 밖. `cause`·`vendorCode` 를 본다 | 보고 |
| `TIMEOUT` | 예 | `callTimeoutMillis` 안에 Provider 호출이 끝나지 않아 중단(0.0.2). 벤더 화면은 남아 있을 수 있다 | 다시 시도 |

### Manager 가 직접 만드는 실패

| 신호 | 언제 |
|---|---|
| `PROVIDER_NOT_REGISTERED` | 등록하지 않은 `ProviderId` 로 부를 때. Provider 를 부르지 않는다 |
| `UNSUPPORTED` | Provider 의 `capabilities` 에 없는 동작을 부를 때. Provider 를 부르지 않는다 |
| `BUSY` | `REJECT` 정책에서 다른 호출이 진행 중일 때 |
| `TIMEOUT` | `callTimeoutMillis` 를 두었고 Provider 호출이 그 안에 끝나지 않을 때. Provider 코루틴을 취소하고 잠금을 푼다 |
| `classify` 결과 + `cause` | Provider 가 결과 대신 예외(`Exception`)를 던질 때 |
| `UNKNOWN` | Provider 가 요청과 다른 수단의 계정을 돌려줄 때 |
| (`providerId` 채움) | Provider 가 `providerId` 없는 `Failure` 를 돌려주면 요청한 수단을 채운다 |

## social-login-kakao

### 예외

| 예외 | 언제 |
|---|---|
| `IllegalArgumentException` | `KakaoLoginConfig.nativeAppKey` 가 비어 있지 않은데 1..128 자 영숫자가 아닐 때(자리표시자 `YOUR_KAKAO_NATIVE_APP_KEY` 포함) |

### 실패 신호

| 신호 | 언제 |
|---|---|
| `Cancelled` | `ClientError(Cancelled)` — 카카오톡 또는 카카오계정 로그인 화면을 사용자가 닫음. 로그인 중 `AuthError(AccessDenied)` — 동의 화면에서 "취소". 카카오톡에서 취소하면 웹 로그인으로 넘어가지 않는다 |
| `CONFIGURATION` (vendorCode 없음) | `nativeAppKey` 가 비어 있음(설정 안 됨 — SDK 를 부르지 않는다). SDK 초기화 실패, 또는 한 프로세스에서 다른 앱 키로 이미 초기화됨(다음 호출이 다시 초기화를 시도한다) |
| `CONFIGURATION` (vendorCode 없음) | nonce 를 넘겼는데 ID 토큰이 오지 않음 — 카카오 디벨로퍼스에서 OpenID Connect 를 켜지 않았다. 기기에는 카카오 SDK 토큰이 저장된 상태다 |
| `UNKNOWN` | 응답의 액세스 토큰이 비었거나, SDK 가 토큰·오류를 모두 비운 콜백을 줌 |
| 아래 표 | `ClientError`·`AuthError`·`ApiError`. vendorCode 는 `ClientError.<원인>`, `AuthError.<원인>(<HTTP 상태>)`, `ApiError.<원인>(<오류 코드>)` |

카카오톡 로그인이 취소 말고 다른 이유로 실패하면 카카오계정(웹) 로그인으로 넘어간다. 웹까지 실패하면 결과는 웹 실패로
분류하고, 카카오톡 쪽 원인은 `cause.suppressed` 에 붙는다.

| SDK 원인 | 갈래 |
|---|---|
| `ClientError`: `TokenNotFound` | `NOT_SIGNED_IN` |
| `ClientError`: `NotSupported` | `UNSUPPORTED` |
| `ClientError`: `BadParameter`·`IllegalState` | `CONFIGURATION` |
| `ClientError`: `Unknown` | `UNKNOWN` |
| `AuthError`: `InvalidRequest`·`InvalidClient`·`InvalidScope`·`Misconfigured`·`Unauthorized` | `CONFIGURATION` |
| `AuthError`: `InvalidGrant` | `AUTHENTICATION` |
| `AuthError`: `AccessDenied` | 로그인이면 `Cancelled`, 그 밖은 `ACCESS_DENIED` |
| `AuthError`: `ServerError` | `PROVIDER_SERVER` |
| `AuthError`: `Unknown` | `UNKNOWN` |
| `ApiError`: `InternalError`·`ApiLimitExceeded`·`ServerTimeOut`·`UnderMaintenance` | `PROVIDER_SERVER` |
| `ApiError`: `IllegalParams`·`BlockedAction`·`PermissionDenied`·`PropertyKeyDoesNotExist`·`AppDoesNotExist`·`DeveloperDoesNotExist` | `CONFIGURATION` |
| `ApiError`: `UnsupportedApi`·`DeprecatedApi`·`NotTalkUser`·`UserDeviceUnsupported` | `UNSUPPORTED` |
| `ApiError`: `NotRegisteredUser` | `NOT_SIGNED_IN` |
| `ApiError`: `InvalidToken`·`AccountDoesNotExist` | `AUTHENTICATION` |
| `ApiError`: `InsufficientScope`·`RequiredAgeVerification`·`UnderAgeLimit` | `ACCESS_DENIED` |
| `ApiError`: 그 밖(`AlreadyRegisteredUser`·`SigningIsNotCompleted`·`InvalidTransaction`·`TransactionHasExpired`·`NotFriend`·`TalkMessageDisabled`·메시지·이미지 한도·`Unknown`) | `UNKNOWN` |
| 이 모듈이 모르는 원인(더 새 SDK) | `UNKNOWN`. vendorCode 에 원인 이름이 남는다. 분류는 원인 이름으로 해 예외가 나지 않는다 |
| SDK 밖 예외 | `IOException` 계열 → `NETWORK`, 나머지 → `UNKNOWN` |

- 로그아웃은 SDK 가 실패를 알려도 기기의 토큰을 지운다(카카오 SDK 동작). **실패 결과여도 기기 로그인 상태는 이미 없다.** 그래서 로그아웃 실패는 보통 안내 없이 넘겨도 된다.
- 연결 해제는 SDK 가 성공했을 때만 기기의 토큰을 지운다. 실패하면 토큰이 남아 다시 시도할 수 있다.

### R8 release 에서만 나는 실패 (공개 0.0.1)

| 신호 | 언제 | 할 일 |
|---|---|---|
| `IllegalArgumentException`: `Call return type must be parameterized as Call<Foo> or Call<? extends Foo>` | 0.0.1 을 Retrofit 2.9.0 그대로 R8 전체 모드로 줄인 앱에서 카카오 API 를 부를 때. 시그니처가 지워진 탓이다 | 0.0.2 로 올리거나 [README](README.md#r8난독화)의 규칙을 앱에 넣는다 |

## social-login-naver

### 예외

| 예외 | 언제 |
|---|---|
| `IllegalArgumentException` | 비어 있지 않은 `NaverLoginConfig.clientId`·`clientSecret` 이 공백 문자 없는 1..128 자가 아니거나, 비어 있지 않은 `clientName` 이 100 자를 넘을 때 |

### 실패 신호

| 신호 | 언제 |
|---|---|
| `Cancelled` | SDK 오류 코드 `CLIENT_USER_CANCEL`, 로그인 중 `SERVER_ERROR_ACCESS_DENIED`(동의 화면에서 "취소") |
| `UNSUPPORTED` | `SignInOptions.nonce` 를 넘김(네이버는 ID 토큰이 없다). SDK 를 부르지 않는다 |
| `CONFIGURATION` (vendorCode 없음) | `clientId`·`clientSecret`·`clientName` 중 빈 값이 있음(설정 안 됨 — SDK 를 부르지 않는다). SDK 초기화 실패, 또는 한 프로세스에서 다른 Client ID 로 이미 초기화됨 |
| `NOT_SIGNED_IN` | 저장된 토큰 없이 연결 해제, 갱신 토큰 없이 갱신. 네이버 서버를 부르지 않는다 |
| `UNKNOWN` | 로그인·갱신 성공 뒤 SDK 의 액세스 토큰이 비어 있음 |
| 아래 표 | 콜백 실패. vendorCode 는 `<오류 코드>(HTTP <상태>)` 또는 `<오류 코드>(error <번호>)` |

분류는 콜백 문구가 아니라 SDK 오류 코드(`NidOAuthErrorCode`)의 이름으로 한다. HTTP 4xx 의 `onFailure` 는 SDK 가 오류 코드를
갱신하지 않아, 호출 전과 코드가 같으면 지난 코드 대신 `NONE` 으로 보고 HTTP 상태로 분류한다.

| SDK 오류 코드 | 갈래 |
|---|---|
| `SERVER_ERROR_INVALID_REQUEST`·`SERVER_ERROR_UNAUTHORIZED_CLIENT`·`SERVER_ERROR_UNSUPPORTED_RESPONSE_TYPE`·`SERVER_ERROR_INVALID_SCOPE` | `CONFIGURATION` |
| `CLIENT_ERROR_NO_CLIENTID`·`NO_CLIENTSECRET`·`NO_CLIENTNAME`·`NO_CALLBACKURL`·`ACTIVITY_IS_SINGLE_TASK`·`SDK_IS_NOT_INITIALIZED` | `CONFIGURATION` |
| `SERVER_ERROR_ACCESS_DENIED` | 로그인이면 `Cancelled`, 그 밖은 `ACCESS_DENIED` |
| `SERVER_ERROR_SERVER_ERROR`·`SERVER_ERROR_TEMPORARILY_UNAVAILABLE` | `PROVIDER_SERVER` |
| `CLIENT_ERROR_CONNECTION_ERROR`·`CLIENT_ERROR_CERTIFICATION_ERROR` | `NETWORK` |
| `WEB_VIEW_IS_DEPRECATED`·`NO_APP_FOR_AUTHENTICATION` | `UNSUPPORTED` |
| `CLIENT_ERROR_PARSING_FAIL` | `UNKNOWN` |
| `NONE`·`ERROR_NO_CATAGORIZED`·이 모듈이 모르는 코드 | HTTP 401 → `AUTHENTICATION`, 5xx → `PROVIDER_SERVER`, 그 밖 → `UNKNOWN` |
| SDK 밖 예외 | `IOException` 계열 → `NETWORK`, 나머지 → `UNKNOWN` |

- 로그아웃은 기기의 토큰만 지운다(네이버 서버의 연동은 남는다). 연동까지 끊으려면 연결 해제를 부른다.
- **연결 해제는 SDK 가 서버 응답을 보기 전에 기기 토큰부터 지운다.** 실패가 `kind.retryable`(망 오류·서버 오류 등)이고 SDK 가 토큰을
  지웠으면 이 라이브러리가 기기 토큰을 되돌린다 — 메시지에 "기기 토큰을 되돌렸다" 가 붙고, 다음 `unlink` 가 서버를 다시 부른다.
  되돌리지 못하면 메시지가 "되돌리지 못했다" 를 알리고 그 원인은 `cause.suppressed` 에 붙는다(다시 로그인 후 연결 해제).
  재시도해도 소용없는 실패는 SDK 동작대로 기기 토큰이 지워진 채다. 호출 코루틴이 취소되면 SDK 요청이 계속되므로 되돌리지 않는다.

### R8 release 에서만 나는 실패 (공개 0.0.1)

| 신호 | 언제 | 할 일 |
|---|---|---|
| 빌드 실패 `Missing class com.google.errorprone.annotations.*`·`org.conscrypt.*`·`org.openjsse.*` | 0.0.1 을 쓰는 앱의 R8 release 빌드. 카카오·Google 을 함께 넣으면 일부만 나온다 | 0.0.2 로 올리거나 [README](README.md#r8난독화)의 규칙을 앱에 넣는다 |
| `ClassCastException`: `Class cannot be cast to ParameterizedType` | 0.0.1 을 Retrofit 2.9.0 그대로 R8 전체 모드로 줄인 앱에서 네이버 API 를 부를 때. 시그니처가 지워진 탓이다 | 위와 같다 |

## social-login-google

### 예외

| 예외 | 언제 |
|---|---|
| `IllegalArgumentException` | 비어 있지 않은 `GoogleLoginConfig.serverClientId` 가 `.apps.googleusercontent.com` 으로 끝나는 공백 없는 28..256 자가 아닐 때 |
| `IllegalArgumentException` | `GoogleLoginConfig`·`GoogleSignInExtension` 의 모드가 `BOTTOM_SHEET` 가 아닌데 `filterByAuthorizedAccounts`·`autoSelectEnabled` 를 켰을 때(조용히 무시하지 않는다) |

### 실패 신호 — 공통

| 신호 | 언제 |
|---|---|
| `CONFIGURATION` (vendorCode 없음) | `serverClientId` 가 비어 있음(설정 안 됨). Google 을 부르지 않는다 |
| `UNSUPPORTED` (vendorCode `TransactionTooLargeException`) | 어느 모드든 계정 선택 화면이 Binder 전송 한도로 뜨지 못함(원인 사슬 16 단계 안, 또는 예외 문구에 그 이름). 기기의 계정이 많을 때의 알려진 문제 |
| `UNSUPPORTED` | 토큰 갱신. 새 ID 토큰은 다시 로그인으로 받는다 |
| `NETWORK` / `UNKNOWN` | SDK 밖 예외 — `IOException` 계열 / 나머지 |

### 실패 신호 — 기본 모드(`LEGACY_GOOGLE_SIGN_IN`)

| 신호 | 언제 |
|---|---|
| `UNSUPPORTED` | `SignInOptions.nonce` 를 넘김(Google Sign-In 은 nonce 를 담을 수 없다). Google 을 부르지 않는다 |
| `Cancelled` | 사용자가 계정 선택을 닫음(상태 코드 12501, 또는 결과 데이터 없는 `RESULT_CANCELED`), 결과 없이 호스트 Activity 가 닫힘 |
| `BUSY` (vendorCode `HostBusy`) | 다른 Google 로그인 화면이 이미 떠 있음 |
| `INTERRUPTED` (vendorCode `HostNotStarted`) | 호스트 Activity 가 10초 안에 뜨지 않음 — 화면이 포그라운드일 때 다시 시도한다 |
| `UNSUPPORTED` (vendorCode `ActivityNotFoundException`) | Google Play 서비스의 계정 선택 화면이 없음 |
| `UNKNOWN` | 결과에 ID 토큰이 없음(`serverClientId` 가 웹 애플리케이션 클라이언트인지 확인), 데이터 없이 성공이 옴 |
| 아래 표 | Google Play 서비스 `ApiException`. vendorCode 는 `ApiException(<상태 코드>)`. `RESULT_CANCELED` 로 와도 결과 데이터를 먼저 해석한다 |

| 상태 코드 | 갈래 |
|---|---|
| 12501 (SIGN_IN_CANCELLED) | `Cancelled` |
| 12502 (SIGN_IN_CURRENTLY_IN_PROGRESS) | `BUSY` |
| 7 (NETWORK_ERROR)·15 (TIMEOUT) | `NETWORK` |
| 10 (DEVELOPER_ERROR) | `CONFIGURATION` — 웹 클라이언트 ID·Android 클라이언트 SHA-1·패키지명 |
| 4 (SIGN_IN_REQUIRED) | `NOT_SIGNED_IN` — 로그인한 계정 없이 연결 해제 |
| 14 (INTERRUPTED)·16 (CANCELED) | `INTERRUPTED` |
| 1·2·3·9·18 (Play 서비스 없음·갱신 필요·꺼짐·잘못됨·갱신 중) | `UNSUPPORTED` |
| 그 밖(12500 SIGN_IN_FAILED·8 INTERNAL_ERROR 등) | `UNKNOWN` |

- 연결 해제는 Google 서버의 권한 철회(`revokeAccess`)이고 기기 로그인 상태도 지운다.
- 로그아웃·연결 해제는 Credential Manager 상태도 보조로 지운다. 그 보조 정리의 실패는 결과에 넣지 않는다.

### 실패 신호 — Credential Manager 모드(`BOTTOM_SHEET`·`BUTTON`)

| 신호 | 언제 |
|---|---|
| `Cancelled` | `GetCredentialCancellationException` — 사용자가 계정 선택을 닫음 |
| `NO_CREDENTIAL` | `NoCredentialException` — 기기에 쓸 Google 계정이 없거나 `filterByAuthorizedAccounts` 에 맞는 계정이 없음. 웹 클라이언트 ID·SHA-1 설정이 틀려도 이렇게 올 수 있다(메시지에 안내) |
| `INTERRUPTED` | `GetCredentialInterruptedException`·`ClearCredentialInterruptedException` |
| `CONFIGURATION` | `GetCredentialProviderConfigurationException`·`ClearCredentialProviderConfigurationException` — Google Play 서비스·`credentials-play-services-auth` 를 찾지 못함 |
| `UNSUPPORTED` | `GetCredentialUnsupportedException`·`ClearCredentialUnsupportedException` — 기기가 Credential Manager 를 지원하지 않음 |
| `UNSUPPORTED` | 연결 해제. 철회는 서버가 Google OAuth 철회 엔드포인트로 한다 |
| (재시도) | `BOTTOM_SHEET` 로그인이 `TransactionTooLargeException` 으로 실패 — 버튼 흐름으로 한 번만 다시 시도한다. 결과는 재시도의 결과이고 첫 예외는 `cause.suppressed` 에 붙는다 |
| `UNKNOWN` | `GetCredentialUnknownException`·`GetCredentialCustomException`·그 밖 하위 타입, Google 이 아닌 자격 증명 타입, 깨진 데이터(`GoogleIdTokenParsingException`), 공백뿐인 ID 토큰 |

vendorCode 는 Credential Manager 예외의 `type` 문자열(예: `android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL`)이다.
로그아웃은 Credential Manager 상태를 지우고 Google Sign-In 상태도 보조로 지운다(보조 정리의 실패는 결과에 넣지 않는다).
