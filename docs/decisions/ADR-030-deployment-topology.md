# ADR-030: 운영 배포 구성 (단일 서버, 프록시 신뢰 범위, 메일, 이미지, 백업)

- 상태: 승인됨 (M6 커밋 1·2·6·7, M6 결정 2·5·6·7·8)
- 날짜: 2026-10-24

## 맥락
- 이 프로젝트의 배포는 실서비스가 아니라 첫 배포 경험과 포트폴리오용이다. 비용이 낮고, 개발자가 직접 이해하고 다룰 수 있는 구성이어야 한다.
- 앱은 상시 실행(Spring Boot), PostgreSQL, 배치(`@Scheduled` + ShedLock), HTTPS(Secure 쿠키·HSTS)가 필요하다.
- 레이트 리미트는 접속 IP로 센다(ADR-012). 리버스 프록시 뒤에서는 모든 요청이 프록시 IP로 보인다.
- prod는 실제 메일 발송기가 없으면 기동하지 않게 해 두었다(ADR-010).

## 결정
- **서버: AWS Lightsail 서울 2GB(월 $12) 한 대 + 스왑 2GB.** 서울이라 지연이 적고, 고정 IP(카카오 어드민 키 허용 IP에 필요)가 있고, 정액이다. JVM 힙 640MB 상한, Postgres `shared_buffers=128MB`. 메모리가 모자라면 4GB로 키운다.
  - 서버리스형(Vercel 등)은 맞지 않다: 요청 단위 실행과 시간 제한이 있고, 크론은 실패해도 다시 돌리지 않는다. 30초 워커와 03:00·00:10·08:00 배치, ShedLock은 상시 프로세스를 전제로 한다.
  - Oracle 무료는 2026년 한도가 예고 없이 줄었고 유휴 회수가 있다. Hetzner는 아시아 리전이 없다. Render 등 PaaS는 JVM에 맞는 크기에서 비용 합계가 더 크다.
- **구성: Docker Compose — postgres · app · caddy** (`docker/compose.prod.yml`). 밖으로 여는 포트는 Caddy의 80·443뿐이다. app(8080)과 postgres(5432)는 내부 네트워크에서만 닿는다(`DeploymentFilesTest`).
- **프록시 신뢰 범위** (커밋 1)
  - `server.forward-headers-strategy=native`로 Tomcat RemoteIpValve를 쓴다. `internal-proxies`는 compose가 Caddy에 준 고정 주소 `172.30.0.10` 하나만이다. 기본값(사설망 전체)보다 좁혀, 같은 네트워크의 다른 컨테이너가 X-Forwarded-For를 꾸며도 믿지 않는다.
  - Caddy는 밖에서 온 X-Forwarded-*를 믿지 않고 실제 접속 IP와 https를 새로 적는다. 그래서 `ClientIp`(`getRemoteAddr`)는 코드 변경 없이 사용자 IP를 돌려주고, X-Forwarded-Proto로 HTTPS를 판정해 HSTS가 나간다.
  - 실제 Tomcat 테스트로 고정: 신뢰 프록시의 XFF는 IP별로 따로 세고 https면 HSTS, 다른 출발지의 XFF·https는 무시(`ForwardedHeadersRealServerTests`).
- **Caddy 로그 가리기** (커밋 6): 접근 로그와 **기본 로거(프록시 오류 로그)** 모두 `request>uri`와 `Referer`의 `/t/{토큰}`을 `/t/***`로 바꾸고 Cookie·Authorization을 지운다. 처음에는 접근 로그만 가렸는데, 로컬 Caddy로 확인하다 앱이 응답하지 않을 때(502)의 오류 로그에 토큰이 그대로 남는 것을 찾아 기본 로거에도 같은 규칙을 넣었다. `/actuator/**`는 밖에서 404다.
- **운영 메일** (커밋 2): SMTP(`spring-boot-starter-mail`) + Brevo 무료 구간(하루 300통). `SmtpEmailSender`(prod)가 ADR-010의 "운영용 메일 구현"이다. 실패하면 주소·본문 없이 예외 종류만 담아 던진다. 알림 메일 채널은 `EmailNotificationSender`(prod)가 맡는다. 세입자 휴대폰 채널은 기본 꺼짐 그대로다. prod는 `OPS_ALERT_EMAIL`도 필수다(결정 8). 메일 업체가 잠깐 안 돼도 헬스체크를 실패로 보지 않는다(`management.health.mail.enabled=false`).
- **이미지와 배포** (커밋 6, 결정 6): GitHub Actions `Image` 워크플로가 버전 태그(`v*`)나 손으로 실행할 때만 GHCR에 올린다(`GITHUB_TOKEN`). 서버 접속 열쇠를 GitHub에 두지 않는다: 배포는 개발자가 서버에서 `pull && up -d`. 이미지는 저장소가 비공개인 동안 비공개로 두고, 서버는 `read:packages`만 있는 토큰으로 한 번 로그인한다(장단점은 runbook 3).
- **백업** (커밋 7, 결정 7): 매일 03:30 `pg_dump -Fc` → gpg 대칭 암호화(평문 덤프를 디스크에 남기지 않음) → 서버 7일 + Cloudflare R2(수명 주기 30일). 암호화 키·HMAC 키·백업 암호는 백업과 다른 곳(비밀번호 관리자 + 오프라인 사본). 복구 리허설은 배포 직후 1회, 그 뒤 월 1회(runbook 7).
- **서버 보안**: SSH 키 로그인만, 22번은 개발자 IP로 제한, 자동 보안 업데이트, 방화벽 22·80·443(runbook 2, 배포 가이드 4·5단계).
- **역할**: Claude Code는 서버에 접속하지 않는다. 명령·설정·문서를 만들고, 개발자가 실행해 비밀값을 뺀 결과를 알려 준다. 운영 `.env`와 DB 개인정보에 닿지 않게 하고, docker 그룹(사실상 root) 권한을 맡기지 않으려는 것이다.

## 확인한 것 (개발 PC, 서버 아님)
- `docker build`로 운영 이미지를 빌드했다(약 530MB, 사용자 `app`, TZ 서울).
- 가짜 값으로 prod 프로필을 띄워 V1~V22 적용과 `/actuator/health` 200을 확인했다. 가짜 값과 컨테이너는 확인 뒤 지웠다.
- `caddy validate`가 통과했고, 실제 요청으로 접근·오류 로그 모두 토큰이 가려지는 것을 봤다.

## 결과
- 서버 한 대라 서버가 죽으면 서비스가 멈춘다(포트폴리오용이라 받아들인다). 복구는 백업 + 새 인스턴스.
- 마이그레이션은 기동 때 Flyway가 돌린다. 배포 전에 백업한다(runbook 4).
- Caddy와 앱의 신뢰 주소가 어긋나면 레이트 리미트가 다시 한 통으로 묶인다. 두 파일을 같은 테스트로 묶었다(`DeploymentFilesTest`).
- 실제 서버에서 확인할 것(IP별 한도, HSTS, 로그 가리기, 메일 도달, BCrypt 시간)은 배포 직후 확인 목록에 있다.
