# ADR-014: 카카오 로그인 (OAuth2)

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- PRD AUTH-2: 카카오 로그인. 이메일 미동의 시에도 가입할 수 있어야 한다.
- 결정: 같은 이메일이어도 이메일 가입(LOCAL)과 카카오 계정을 **자동으로 합치지 않는다**(계정 연결은 P1).
- 카카오는 Spring Security의 기본 제공자 목록(Google, GitHub 등)에 없다.
- 카카오 키(REST API 키, 클라이언트 시크릿, 서비스 앱 어드민 키)는 비밀값이다. local·CI에는 없을 수 있다.

## 결정
1. **아티팩트**: `spring-boot-starter-security-oauth2-client`(Boot 4.1.1, 내부에 `spring-security-oauth2-jose` 포함).
2. **등록을 코드로 한다**(`KakaoOAuth2Config`). `spring.security.oauth2.client.*` 프로퍼티를 쓰면 키가 없을 때 Boot가 빈 client-id로 기동에 실패한다.
   - 키 3개(`KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_ADMIN_KEY`)가 **모두 없으면** local·CI는 카카오 로그인을 끄고(버튼 숨김) 정상 기동한다. 운영(prod)은 기동하지 않는다.
   - **일부만 있으면** 설정 실수로 보고 기동하지 않는다.
   - 등록 내용: `client_secret_post`(카카오는 시크릿을 토큰 요청 본문으로 받는다), `authorization_code`, 리다이렉트 URI `{baseUrl}/login/oauth2/code/kakao`, scope `account_email, profile_nickname`(최소 수집), `user-name-attribute: id`.
   - 주소: 인가 `https://kauth.kakao.com/oauth/authorize`, 토큰 `https://kauth.kakao.com/oauth/token`, 사용자 정보 `https://kapi.kakao.com/v2/user/me`. 테스트에서 가짜 서버로 바꿀 수 있게 설정값으로 둔다.
3. **토큰을 저장하지 않는다**(`NoStoreAuthorizedClientRepository`). Boot 기본값은 메모리에 보관한다. 우리는 로그인 때 사용자 정보를 한 번 읽는 데만 토큰을 쓴다. 연결 끊기는 어드민 키로 한다(ADR-015).
4. **저장하는 정보**: 회원번호(`kakao_id`), 동의한 경우 이메일, 닉네임(이름 기본값으로만 쓰고 수정 가능).
   - 이메일은 `is_email_valid`이고 `is_email_verified`일 때만 "인증됨"으로 저장한다. 유효하지만 미인증이면 우리 인증 메일을 보낸다. `is_email_valid=false`(다른 카카오계정에 쓰여 만료된 주소)이거나 우리 형식(ASCII)에 맞지 않으면 쓰지 않는다.
   - 세션에 들어가는 principal에는 카카오 사용자 정보를 담지 않는다(`getAttributes()`는 우리 id만).
5. **principal**: 가입을 마친 임대인은 이메일·카카오 로그인 모두 `LandlordPrincipal`(UserDetails + OAuth2User)을 쓴다. `getName()` = `getUsername()` = landlord id.
   - 처음 온 카카오 사용자는 계정을 만들지 않고 임시 `KakaoSignupPrincipal`(`ROLE_KAKAO_SIGNUP`)로 가입 단계(`/signup/kakao`)에 보낸다. 동의 없이 계정이 생기지 않게 하기 위해서다.
   - 임시 principal의 이름은 `kakao-signup:{무작위 UUID}`다. 처음에는 `kakao-signup:{회원번호}`였는데, Spring Session이 이 이름을 `spring_session.principal_name`(인덱스가 걸린 평문 컬럼)에 저장해 가입을 마치지 않은 사람의 회원번호가 그대로 보였다. 그래서 무작위 값으로 바꿨다. `toString()`에도 회원번호를 넣지 않는다.
   - **한계:** 가입을 마치려면 회원번호가 필요하므로 세션 속성(principal을 직렬화한 값, `spring_session_attributes`)에는 회원번호가 **여전히 들어 있다**. 이 수정의 효과는 조회·인덱스가 쉬운 principal_name 컬럼에서 평문 노출을 줄이는 데까지다. 세션은 2시간 유휴 뒤 만료되어 지워진다.
6. **계정 찾기는 회원번호 + ACTIVE로만** 한다. 같은 이메일의 LOCAL 계정이 있어도 새 카카오 가입으로 이어진다(자동 병합 금지). 탈퇴 유예 중인 계정도 새 가입으로 처리한다(ADR-015).
7. **CSP**: 카카오 로그인은 링크(GET `/oauth2/authorization/kakao`)로 시작한다. 폼 POST 뒤에 다른 사이트로 리다이렉트하면 Chrome이 `form-action 'self'`로 막기 때문이다.
8. state 검증, 로그인 성공 시 세션 id 교체는 Spring Security 기본 동작을 쓰고 테스트로 확인했다.
9. 카카오 로그인에는 remember-me를 붙이지 않는다. 콜백 요청에 체크박스 값이 없기 때문이다. 다시 로그인하기 쉬우므로 MVP에서는 그대로 둔다.

## PKCE: 계획 단계의 설명 오류와 선택
- **계획 단계의 틀린 설명**: "Security 7.1.1은 인증 방식이 `none`이거나 `requireProofKey`일 때만 PKCE를 적용하므로, `client_secret_post`인 우리 설정에서는 보내지 않는다"고 설명했다. 조건문(`DefaultOAuth2AuthorizationRequestResolver` 206행)만 보고 **기본값을 확인하지 않았다**.
- **실제 동작**: `ClientRegistration`을 빌더로 만들면 `ClientSettings.requireProofKey`의 **기본값이 `true`**다(`ClientRegistration.java` 393·795행). 그래서 우리 카카오 등록도 PKCE를 보낸다. 인가 요청에 `code_challenge`(S256)를, 토큰 요청에 `code_verifier`를 넣는다. 가짜 카카오 서버 테스트(`KakaoLoginTests`)에서 처음 드러났다.
- **카카오 지원 여부**: 카카오 REST API 문서에서 PKCE 지원 여부를 **확인하지 못했다**(언급 없음).
- **선택: PKCE를 켠 채로 둔다**(Security 7 기본값).
  - OAuth 2.1 권고에 맞고, 인가 코드를 가로채는 공격에 대한 방어가 한 겹 더해진다.
  - 카카오가 PKCE를 지원하지 않더라도 모르는 파라미터를 무시하면 영향이 없다.
  - 실제 동작은 개발자의 브라우저 확인(카카오 로그인)에서 바로 드러난다.
- **대응책**: 실제 카카오가 PKCE 파라미터를 거부해 로그인이 실패하면, `KakaoOAuth2Config.kakaoRegistration`에 `.clientSettings(ClientSettings.builder().requireProofKey(false).build())`를 넣어 끈다. 그때도 기본 보호는 `client_secret`과 `state`로 유지된다. 테스트 기대값(`code_challenge` 있음, `code_verifier` 포함)도 함께 바꾼다.

## 검토한 대안
| 대안 | 채택하지 않은 이유 |
|---|---|
| `spring.security.oauth2.client.*` 프로퍼티로 등록 | 키가 없는 local·CI에서 기동 실패 |
| 카카오 콜백에서 바로 계정 생성 | 약관 동의 없이 계정이 생긴다 |
| 같은 이메일이면 기존 LOCAL 계정에 연결 | 결정 사항(자동 병합 금지). 이메일만으로 소유를 단정할 수 없다 |
| 액세스 토큰 보관(세션·메모리) | 쓰지 않는 토큰을 남길 이유가 없다. 유출 위험만 생긴다 |
| PKCE 끄기(계획의 원래 의도) | 카카오가 PKCE를 거부한다는 근거가 없다. 거부되면 그때 끈다 |

## 결과
- 진짜 카카오 대신 가짜 카카오 서버(JDK `HttpServer`)로 인가 → state → 토큰 → 사용자 정보 → 연결 끊기 경로를 테스트한다. 실제 카카오 계정 확인은 개발자가 브라우저로 한다.
- 운영 도메인이 정해지면 카카오 콘솔에 리다이렉트 URI를 추가하고, 프록시 뒤에서 `{baseUrl}`이 운영 주소로 계산되는지 확인한다(M6, `forward-headers-strategy`).
