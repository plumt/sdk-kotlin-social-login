# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## social-login-core

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `SocialLoginProvider<H>` 계약 — 로그인과 선택 동작(`Capability`: 로그아웃·연결 해제·토큰 갱신). 새 수단은 이 인터페이스를 구현한 모듈로 붙인다
- `ProviderId` 값 객체(`KAKAO`·`NAVER`·`GOOGLE` 상수, 새 식별자 허용)
- `SocialAccount`·`SocialToken`·`SignInOptions`(nonce) — `toString` 이 토큰·이메일·이름·nonce 를 가린다
- `SocialLoginResult` = `Success` / `Cancelled` / `Failure(kind, message, providerId, vendorCode, cause)`, `FailureKind`(재시도 가능 여부 포함)
- `SocialLoginErrors` — 수단과 무관한 예외 분류(원인 사슬의 망 예외 → `NETWORK`)
- `SocialLoginManager` — 등록 순서 보존, 중복 등록 거절, 미등록·미지원 동작은 Provider 를 부르지 않는 실패, Provider 예외 감싸기, 동시 호출 정책 `ConcurrencyPolicy`(`REJECT` 기본·`SERIALIZE`)

## social-login-kakao

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `KakaoLogin.provider` · `KakaoLoginConfig` — 카카오톡 앱 로그인, 사용자 취소가 아니면 카카오계정 로그인 폴백, nonce(OIDC)
- 로그아웃·연결 해제·토큰 갱신
- `ClientError`·`AuthError`·`ApiError` 의 모든 원인을 `FailureKind` 로 분류
- 프로세스 전역 SDK 초기화를 첫 호출 때 한 번만(`Application` 수정 불필요), 다른 키로의 재초기화는 `CONFIGURATION`

## social-login-naver

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `NaverLogin.provider` · `NaverLoginConfig` — 네이버 로그인, 로그아웃(기기 토큰), 연결 해제(서버 토큰 삭제), 토큰 갱신
- 콜백 문구가 아니라 SDK 오류 코드(`NidOAuthErrorCode`)로 분류. nonce 요청은 `UNSUPPORTED`

## social-login-google

### [Unreleased]

### [0.0.1] - 미발행

#### 추가
- `GoogleLogin.provider` · `GoogleLoginConfig` · `GoogleSignInMode` — Credential Manager 의 계정 선택 시트(`GetGoogleIdOption`)와 버튼 흐름(`GetSignInWithGoogleOption`), nonce
- 로그아웃(자격 증명 상태 지우기). 연결 해제·갱신은 `UNSUPPORTED`
- Credential Manager 예외를 `FailureKind` 로 분류
- 계정이 많은 기기의 `TransactionTooLargeException` 완화 — 시트 실패 시 버튼 흐름으로 1회 자동 재시도, 그래도 실패하면 `UNSUPPORTED` + vendorCode `TransactionTooLargeException`
