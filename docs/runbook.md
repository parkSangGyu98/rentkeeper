# 운영 runbook

> RentKeeper 운영 절차 (M6, ADR-030·031). 처음 배포는 `docs/deploy-checklist.md`를 순서대로 따라 한다. 이 문서는 그 뒤 반복하는 일과 문제가 생겼을 때 볼 곳이다.
> 이 문서에는 비밀값을 적지 않는다. 명령의 `<…>` 자리는 직접 채운다. 명령은 개발자가 서버에서 실행한다(Claude Code는 서버에 접속하지 않는다, M6 결정).

## 0. 가장 먼저: 키를 잃으면 복구할 수 없다
- `APP_ENCRYPTION_KEY`(개인정보 암호화), `APP_HMAC_KEY`(전화번호 해시), `APP_REMEMBER_ME_KEY`, `BACKUP_PASSPHRASE`는 **서버의 `.env.prod` 말고도 두 곳**에 둔다: 비밀번호 관리자 + 오프라인 사본(종이 또는 암호화한 USB).
- **백업과 같은 곳에 두지 않는다.** 백업 저장소(R2)가 새어도 키가 없으면 풀 수 없게 하려는 것이다.
- `APP_ENCRYPTION_KEY`를 잃으면 백업이 있어도 세입자 이름·연락처·계좌·메모를 되살릴 수 없다. `BACKUP_PASSPHRASE`를 잃으면 백업 파일 자체를 풀 수 없다.

## 1. 구성 한눈에
| 무엇 | 어디 |
|---|---|
| 서버 | AWS Lightsail 서울, Ubuntu, 2GB (모자라면 4GB) |
| 컨테이너 | `docker/compose.prod.yml`: postgres(16-alpine), app(GHCR 이미지), caddy(2-alpine) |
| 밖으로 여는 포트 | 22(SSH, 내 IP만), 80·443(Caddy) |
| 설정 | 저장소 루트의 `.env.prod`(권한 600, git에 없음). 이름 목록은 `.env.prod.example` |
| 로그 | `docker compose … logs app`, `… logs caddy` (컨테이너마다 10MB × 5개까지) |
| 백업 | 서버 `~/backups`(7일) + R2 버킷 |

아래 명령의 `$C`는 저장소 폴더에서 이렇게 줄여 쓴다:
```bash
cd ~/rentkeeper
C="docker compose --env-file .env.prod -f docker/compose.prod.yml"
```

## 2. 서버 보안 기본 (처음 한 번, 그 뒤 가끔 확인)
- **SSH는 키로만**: `/etc/ssh/sshd_config`(또는 `/etc/ssh/sshd_config.d/*.conf`)에 `PasswordAuthentication no`, `PermitRootLogin no`. 바꾼 뒤 `sudo systemctl restart ssh`. **바꾸기 전에 키로 접속되는 새 창을 하나 열어 둔다**(잠기지 않게).
- **방화벽**: Lightsail 콘솔 → 인스턴스 → 네트워킹 → IPv4 방화벽에 SSH(22)·HTTP(80)·HTTPS(443)만 둔다. SSH 규칙은 가능하면 "내 IP로 제한". 집 IP가 바뀌면 콘솔에서 고친다(콘솔의 브라우저 SSH는 이 규칙과 별개로 동작한다). IPv6 방화벽도 같은 규칙으로 맞춘다.
- **자동 보안 업데이트**: `sudo apt install unattended-upgrades` → `sudo dpkg-reconfigure -plow unattended-upgrades`(Yes). 커널 업데이트 뒤에는 시간을 정해 `sudo reboot`.
- **Docker**: compose 파일이 app·postgres 포트를 열지 않는다(`DeploymentFilesTest`). `docker ps`에서 `0.0.0.0:5432` 같은 줄이 보이면 안 된다.
- `.env.prod`는 `chmod 600 .env.prod`. 셸 기록에 비밀값을 남기지 않게 값은 편집기로 넣는다(명령줄에 붙여 넣지 않는다).

## 3. 이미지 받기 (GHCR)
이미지는 GitHub Actions(`Image` 워크플로)가 버전 태그(`v*`)를 올릴 때 `ghcr.io/<계정>/rentkeeper:<태그>`로 만든다.

| 방식 | 장점 | 단점 |
|---|---|---|
| **비공개 이미지 + 읽기 전용 토큰** (권장) | 저장소가 비공개인 동안 코드(jar)가 밖에 보이지 않는다 | 서버에 토큰이 저장된다(`~/.docker/config.json`, base64라 사실상 평문). 토큰 만료 관리가 필요하다 |
| 공개 이미지 | 서버에 토큰이 없다. 받기가 간단하다 | jar를 누구나 받아 코드를 볼 수 있다(비밀값은 이미지에 없지만 구조가 드러난다) |

**권장: 저장소가 비공개인 지금은 비공개 이미지.** README를 정리해 저장소를 공개할 때 이미지도 공개로 바꾸고 서버에서 `docker logout ghcr.io` 한다.
- 토큰: GitHub → Settings → Developer settings → Personal access tokens (classic) → **`read:packages`만** 체크, 만료일을 정한다(예: 90일). 토큰 값은 이 문서·저장소·채팅 어디에도 적지 않는다.
- 서버에서 한 번만 로그인한다(토큰은 화면에 보이지 않게 입력):
  ```bash
  read -rs GHCR_TOKEN && echo "$GHCR_TOKEN" | docker login ghcr.io -u <GitHub 계정> --password-stdin; unset GHCR_TOKEN
  chmod 600 ~/.docker/config.json
  ```
- 토큰이 만료되면 pull이 `unauthorized`로 실패한다. 새 토큰으로 위를 다시 한다.

## 4. 배포 (새 버전)
1. 로컬에서 `main`이 CI 통과한 상태인지 본다. 버전 태그를 만들어 올린다: `git tag v0.6.1 && git push origin v0.6.1` (push는 개발자가 한다).
2. GitHub → Actions → `Image`가 성공했는지 본다.
3. 서버: **먼저 백업**(Flyway가 기동 때 마이그레이션을 돌린다): `scripts/backup.sh`
4. `.env.prod`의 `APP_IMAGE` 태그를 새 버전으로 고친다. 지금 태그는 적어 둔다(되돌리기용).
5. `$C pull app && $C up -d`
6. 확인: `$C ps`(app이 healthy), `$C logs --tail 100 app`에 `Started RentKeeperApplication`과 `Successfully applied … migrations`, 브라우저로 첫 화면·로그인.

## 5. 되돌리기
- 앱만 문제: `.env.prod`의 `APP_IMAGE`를 이전 태그로 되돌리고 `$C up -d`.
- **마이그레이션이 적용된 뒤**라면 예전 앱이 새 스키마에서 안 뜰 수 있다. 우리 마이그레이션은 컬럼을 더하기만 해서(하위 호환) 대개 그대로 뜬다. 안 뜨면 4번 3단계의 백업으로 복구한다(아래 7).

## 6. 백업
- cron (서버, `crontab -e`):
  ```
  30 3 * * * cd /home/ubuntu/rentkeeper && scripts/backup.sh >> /home/ubuntu/backups/backup.log 2>&1
  ```
- 스크립트가 하는 일: postgres 안에서 `pg_dump -Fc` → gpg(AES256, `BACKUP_PASSPHRASE`)로 바로 암호화(평문 덤프를 디스크에 남기지 않음) → `~/backups`에 7일치 → R2 버킷에 업로드(aws-cli 컨테이너).
- R2 쪽 보관: 버킷 설정의 수명 주기 규칙으로 30일 뒤 지우게 한다.
- 확인: `tail backup.log`에 `backup: rentkeeper-…dump.gpg (…) 완료`. R2 콘솔에 파일이 생겼는지 가끔 본다.

## 7. 복구 리허설 (배포 직후 1회, 그 뒤 월 1회) — 운영 DB는 건드리지 않는다
로컬 PC에서 한다. 운영 키가 필요하므로 끝나면 로컬에 남은 키·덤프를 지운다.
1. R2에서 최신 백업 파일을 내려받는다(콘솔 또는 aws-cli).
2. 복호화: `gpg --batch --pinentry-mode loopback --decrypt --output restore.dump rentkeeper-<날짜>.dump.gpg` (암호를 물으면 `BACKUP_PASSPHRASE`)
3. 빈 Postgres를 띄운다: `docker run -d --name rk-restore -e POSTGRES_PASSWORD=<임시> -p 127.0.0.1:55432:5432 postgres:16-alpine`
4. 복원: `docker cp restore.dump rk-restore:/tmp/ && docker exec rk-restore pg_restore -U postgres -d postgres --no-owner /tmp/restore.dump`
5. 건수 확인(개인정보 없이): `docker exec rk-restore psql -U postgres -c "select (select count(*) from landlord) l, (select count(*) from lease) le, (select count(*) from charge) c, (select max(version) from flyway_schema_history) v;"`
6. 복호화 확인: 로컬 앱을 운영 키(`APP_ENCRYPTION_KEY`·`APP_HMAC_KEY`·키 버전)와 이 DB(`DB_URL=jdbc:postgresql://localhost:55432/postgres`)로 띄워, 계약 상세에서 세입자 이름이 정상 글자로 보이는지 본다.
7. 걸린 시간과 결과를 `docs/progress.md`에 한 줄 적는다. `docker rm -f rk-restore`, `restore.dump`·백업 파일·임시 .env를 지운다.

## 8. 키 교체 (필요할 때만)
- 개인정보 암호화 키: 새 키를 `APP_ENCRYPTION_KEY`에, 버전을 하나 올려 `APP_ENCRYPTION_KEY_VERSION`에, 예전 키를 `APP_ENCRYPTION_PREVIOUS_KEYS`에 `버전:키`로 넣고 재시작한다(ADR-017). 예전 데이터는 읽을 때 예전 키로 풀린다. 예전 키는 모든 행을 다시 저장하기 전까지 지우지 않는다.
- `APP_HMAC_KEY`는 바꾸면 같은 번호 찾기가 깨진다. 바꾸지 않는다.
- remember-me 키를 바꿔도 기존 로그인 유지는 풀리지 않는다. 모두 끊으려면 `persistent_logins`를 비운다(ADR-013).

## 9. 배포 뒤 점검 항목 (처음 한 번, 설정을 바꿀 때마다)
- **Caddy 로그 가리기**: 세입자 링크를 한 번 연 뒤 `$C logs caddy | grep '/t/'` → 모든 줄이 `/t/***`이고 토큰 글자가 없다. `Referer`도 같다. 앱을 잠깐 멈춘 상태(502)의 오류 로그도 가려진다(로컬에서 확인함).
- **IP별 레이트 리미트**: 두 곳(예: 집 와이파이, 휴대폰 데이터)에서 세입자 링크를 열고 DB에서 `select bucket_key from rate_limit_bucket where bucket_key like 'tenant-link:ip:%';` → 서로 다른 IP 두 줄(Caddy 주소 172.30.0.10이 아니어야 한다).
- **HTTPS**: 응답 헤더에 `Strict-Transport-Security`, 로그인 쿠키에 `Secure`.
- **Origin 검사**: 실제 브라우저로 세입자 [입금했어요]·[안내 문자 그만 받기]를 눌러 403("다시 열어 주세요")이 나오지 않는다. 나오면 `APP_BASE_URL`이 실제 주소와 같은지 본다(끝 `/`, `https`).
- **BCrypt 시간**: 로그인 한 번이 1초 넘게 걸리면 cost 12를 다시 본다(ADR-009).

## 10. 문제가 생겼을 때
| 증상 | 볼 곳 · 할 일 |
|---|---|
| 앱이 안 뜬다 (`Restarting`) | `$C logs --tail 200 app`. `IllegalStateException`의 문구가 빠진 환경변수를 알려 준다(메일·카카오·초대 코드·운영 알림 주소·키) |
| 인증서 발급 실패 | `$C logs caddy`. DNS A 레코드가 고정 IP를 가리키는지, 80·443이 열렸는지. 실패를 반복하면 Let's Encrypt 한도에 걸리므로 원인을 고친 뒤 다시 띄운다 |
| 메모리 부족 (`OutOfMemoryError`로 재시작) | `free -m`, 스왑 확인. 계속되면 4GB 인스턴스로 키운다(스냅샷 → 새 인스턴스 → 고정 IP 옮기기) |
| 디스크 부족 | `df -h`, `docker system df`. 오래된 이미지 `docker image prune`. 백업 폴더 확인 |
| 메일이 안 간다 | 알림 이력 화면의 "보내지 못했어요", 앱 로그의 `smtp send failed: …`(예외 종류만). Brevo 하루 300통 한도, 도메인 인증(SPF·DKIM) |
| 운영 알림 메일이 왔다 | 제목의 코드로 `$C logs app | grep <코드>`: `CHARGE_BATCH_ALERT`(청구 배치), `NOTIFICATION_ALERT`(알림 발송), `KAKAO_UNLINK_ALERT`(카카오 연결 끊기) |

## 11. 카카오 연결 끊기 대기 행 정리 (ADR-015)
탈퇴 30일 뒤 연결 끊기에 계속 실패하는 회원번호는 `kakao_unlink_pending`에 남는다. `KAKAO_UNLINK_ALERT`가 며칠 이어지면:
1. 카카오 개발자 콘솔에서 앱의 어드민 키·허용 IP(서버 고정 IP)를 확인한다.
2. 그래도 `REJECTED`(카카오가 거부)로 남는 행은 콘솔에서 연결 상태를 확인한 뒤 수동으로 지운다: `delete from kakao_unlink_pending where kakao_id = '<회원번호>';` (한 줄씩)

## 12. 데모 초기화
- 데모 계정은 만든 지 24시간이 지나면 매시 20분에 지워진다(`DemoAccountPurgeJob`).
- 한꺼번에 지우려면(예: 이상한 데이터가 보일 때): `$C exec postgres psql -U <DB_USER> -d <DB_NAME> -c "update landlord set demo_expires_at = now() where demo_expires_at is not null;"` → 다음 정각 20분에 지워진다.
- 데모 수 상한(`APP_DEMO_MAX_ACCOUNTS`)이나 시간(`APP_DEMO_LIFETIME`)을 바꾸면 `$C up -d app`.
