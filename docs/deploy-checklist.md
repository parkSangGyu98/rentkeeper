# 처음 배포하기 — 사람이 하는 일 단계별 가이드

> RentKeeper를 처음 인터넷에 올릴 때 **사람이 직접 하는 일**을 처음부터 순서대로 적었다(M6). 개발 지식이 많지 않아도 따라 할 수 있게 썼다.
> - `<…>`나 빈칸은 직접 채운다. **비밀값(비밀번호·키·토큰)은 이 문서·채팅·스크린샷 어디에도 붙이지 않는다.** 서버의 `.env.prod`와 비밀번호 관리자에만 둔다.
> - Claude Code는 서버에 접속하지 않는다. 막히면 화면에 나온 내용에서 비밀값을 지우고 알려 주면 다음 할 일을 정리해 준다.
> - 반복하는 일(새 버전 배포, 백업 확인, 문제 해결)은 `docs/runbook.md`.
> - 각 단계를 마치면 `[ ]`를 `[x]`로 바꿔 둔다.

## 0. 미리 준비할 계정
- [ ] AWS 계정 (Lightsail, 결제 카드 등록)
- [ ] 도메인을 살 곳 (가비아·Cloudflare·Namecheap 등). Cloudflare에서 사면 DNS와 R2를 한곳에서 관리할 수 있다
- [ ] Cloudflare 계정 (백업 저장소 R2)
- [ ] Brevo 계정 (메일 발송, 무료)
- [ ] GitHub 계정 (이미 있음), 카카오 개발자 계정 (이미 있음)
- [ ] 비밀번호 관리자 (1Password·Bitwarden 등). 이 가이드에서 만드는 비밀값을 모두 여기에 저장한다

예상 비용: Lightsail 2GB 월 $12(약 1만 7천 원), 도메인 연 1~2만 원 안팎. 메일·백업은 무료 구간 안이다. 구매 전 화면의 가격을 다시 확인한다.

## 1. SSH 키 만들기 (내 PC)
서버에 비밀번호 대신 "열쇠 파일"로 접속하기 위한 것이다.
- [ ] PowerShell에서: `ssh-keygen -t ed25519 -C "rentkeeper-server"` → 저장 위치는 Enter(기본값), 암호(passphrase)는 정해서 넣는다
- [ ] 만들어진 파일 두 개: `C:\Users\<나>\.ssh\id_ed25519`(**비밀 열쇠, 절대 남에게 주지 않는다**)와 `id_ed25519.pub`(공개 열쇠, 서버에 올린다)
- [ ] 비밀 열쇠의 암호를 비밀번호 관리자에 저장한다

## 2. Lightsail 서버 만들기
- [ ] AWS 콘솔 → Lightsail → **인스턴스 생성**
- [ ] 위치: **서울 (ap-northeast-2)**
- [ ] 플랫폼 Linux/Unix → 블루프린트 **OS 전용 → Ubuntu 24.04 LTS**
- [ ] SSH 키: "키 업로드"로 1단계의 `id_ed25519.pub`를 올린다
- [ ] 플랜: **2GB 메모리 (월 $12)** (화면의 가격 확인)
- [ ] 인스턴스 이름: `rentkeeper-prod` → 생성

## 3. 고정 IP 만들기
서버를 다시 켜도 주소가 바뀌지 않게 한다. 카카오 어드민 키 허용 IP와 도메인이 이 주소를 쓴다.
- [ ] Lightsail → 네트워킹 → **고정 IP 생성** → 서울, 위 인스턴스에 연결
- [ ] 고정 IP 주소를 적어 둔다: `________________` (비밀값 아님)

## 4. 방화벽
- [ ] 인스턴스 → 네트워킹 → **IPv4 방화벽**: SSH(22), HTTP(80), HTTPS(443)만 남긴다
- [ ] SSH(22) 규칙 편집 → **"IP 주소로 제한"** → 내 집 IP(네이버에서 "내 IP" 검색)를 넣는다. 집 IP가 바뀌면 여기서 고친다
- [ ] IPv6 방화벽도 같은 규칙으로 (IPv6를 쓰지 않으면 끈다)

## 5. 서버에 접속해 기본 설정
- [ ] 내 PC PowerShell: `ssh ubuntu@<고정 IP>` (처음 물으면 `yes`)
- [ ] 업데이트: `sudo apt update && sudo apt -y upgrade`
- [ ] 시간대: `sudo timedatectl set-timezone Asia/Seoul`
- [ ] Docker 설치: `sudo apt -y install docker.io docker-compose-v2` → `sudo usermod -aG docker ubuntu` → **접속을 끊었다가 다시 접속**
- [ ] 확인: `docker compose version`
- [ ] 스왑 2GB (메모리가 모자랄 때 대비):
  ```bash
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  ```
- [ ] 자동 보안 업데이트: `sudo apt -y install unattended-upgrades` → `sudo dpkg-reconfigure -plow unattended-upgrades` → Yes
- [ ] **비밀번호 로그인 끄기** (키 접속이 되는 것을 확인한 뒤):
  ```bash
  printf 'PasswordAuthentication no\nPermitRootLogin no\n' | sudo tee /etc/ssh/sshd_config.d/90-rentkeeper.conf
  sudo systemctl restart ssh
  ```
  지금 창을 닫기 전에 **새 PowerShell 창에서 `ssh ubuntu@<고정 IP>`가 되는지** 확인한다.

## 6. 도메인과 DNS
- [ ] 도메인 구매: `________________`
- [ ] DNS에 **A 레코드**: 이름 `@`(또는 쓰려는 하위 이름), 값 = 3단계의 고정 IP. Cloudflare DNS라면 **프록시(주황 구름)는 끈다**(회색 구름, "DNS only") — Caddy가 직접 인증서를 받아야 한다
- [ ] 확인(내 PC): `nslookup <도메인>` → 고정 IP가 나온다 (몇 분~몇 시간 걸릴 수 있다)

## 7. 서버에 배포 파일 올리기
저장소 전체가 아니라 운영에 필요한 파일만 올린다(저장소가 비공개라 서버에 GitHub 접속 열쇠를 두지 않는다).
- [ ] 서버: `mkdir -p ~/rentkeeper ~/backups`
- [ ] 내 PC(저장소 폴더, PowerShell): `scp -r docker scripts .env.prod.example ubuntu@<고정 IP>:~/rentkeeper/`
- [ ] 서버: `chmod +x ~/rentkeeper/scripts/backup.sh`
- 나중에 이 파일들이 바뀌면 같은 명령으로 다시 올린다.

## 8. 메일(Brevo) 준비
- [ ] Brevo 가입 → **Senders, Domains & Dedicated IPs → Domains → Add a domain** → 6단계 도메인
- [ ] Brevo가 보여 주는 DNS 레코드(인증용 TXT, DKIM, DMARC)를 6단계 DNS에 그대로 추가 → Brevo에서 **Verify**. 인증이 끝나야 메일이 스팸함으로 덜 간다
- [ ] **SMTP & API → SMTP** → SMTP 키 생성. 화면의 SMTP 서버(`smtp-relay.brevo.com`), 포트(587), 로그인(아이디)을 적어 두고, **SMTP 키는 비밀번호 관리자에만** 저장
- [ ] 보내는 주소를 정한다: `no-reply@<도메인>`

## 9. 백업 저장소(Cloudflare R2) 준비
- [ ] Cloudflare → **R2** 활성화 (무료 구간이라도 결제 수단 등록을 요구할 수 있다)
- [ ] 버킷 만들기: 이름 `rentkeeper-backup`(예시), 위치 자동
- [ ] 버킷 설정 → **수명 주기 규칙**: 30일 지난 객체 삭제
- [ ] **R2 API 토큰 관리 → 토큰 생성**: 권한 "객체 읽기 및 쓰기", **이 버킷만**. 화면의 Access Key ID·Secret Access Key·엔드포인트(`https://<계정 ID>.r2.cloudflarestorage.com`)를 비밀번호 관리자에 저장 (Secret은 한 번만 보인다)

## 10. 카카오 콘솔 설정
- [ ] [카카오 개발자](https://developers.kakao.com) → 내 애플리케이션 → 앱 이름을 **RentKeeper**로
- [ ] 앱 설정 → 플랫폼 → Web → 사이트 도메인에 `https://<도메인>` 추가
- [ ] 카카오 로그인 → Redirect URI에 `https://<도메인>/login/oauth2/code/kakao` 추가 (로컬용 주소는 지우지 않는다)
- [ ] 보안 → **Client Secret 재발급**: 개발하며 쓰던 값 대신 운영용 새 값을 쓴다. 재발급하면 로컬 `.env`의 `KAKAO_CLIENT_SECRET`도 새 값으로 바꿔야 로컬 카카오 로그인이 계속 된다
- [ ] 앱 키 → 어드민 키 → **허용 IP 주소**에 3단계 고정 IP 추가
- [ ] REST API 키(=`KAKAO_CLIENT_ID`), Client Secret, 어드민 키를 비밀번호 관리자에서 확인 (화면에 띄워 둔 채 공유하지 않는다)

## 11. GitHub 설정
- [ ] 저장소 → Settings → Branches → `main` 보호 규칙: PR을 거쳐 병합, **CI 통과 필수**
- [ ] 이미지 받기용 토큰: Settings(내 계정) → Developer settings → Personal access tokens (classic) → **`read:packages`만** 체크, 만료일 90일 → 비밀번호 관리자에 저장
- [ ] 첫 이미지 만들기: 내 PC에서 `git tag v0.6.0` → `git push origin v0.6.0` → GitHub → Actions → **Image**가 초록색인지 확인
- [ ] 이미지 이름 적어 두기: `ghcr.io/<계정 소문자>/rentkeeper:v0.6.0`

## 12. 운영 설정 파일(.env.prod) 만들기 (서버)
- [ ] `cd ~/rentkeeper && cp .env.prod.example .env.prod && chmod 600 .env.prod`
- [ ] 키 만들기: 아래 명령을 하나씩 실행해 나온 값을 **편집기로** `.env.prod`의 해당 줄에 넣는다. 나온 값은 비밀번호 관리자에도 저장한다. 화면을 캡처하거나 붙여 넣어 공유하지 않는다.

  | 이름 | 만드는 명령 |
  |---|---|
  | `DB_PASSWORD` | `openssl rand -hex 24` |
  | `APP_REMEMBER_ME_KEY` | `openssl rand -hex 32` |
  | `APP_ENCRYPTION_KEY` | `openssl rand -base64 32` |
  | `APP_HMAC_KEY` | `openssl rand -base64 32` |
  | `APP_SIGNUP_INVITE_CODE` | `openssl rand -hex 12` (24자) |
  | `BACKUP_PASSPHRASE` | `openssl rand -base64 32` |

- [ ] 편집: `nano .env.prod` → 아래처럼 채운다(나머지 줄 설명은 파일 안에 있다). 저장은 `Ctrl+O` → Enter, 나가기는 `Ctrl+X`

  | 이름 | 넣을 값 |
  |---|---|
  | `DOMAIN` | 6단계 도메인 (`https://` 없이) |
  | `ACME_EMAIL`, `OPS_ALERT_EMAIL` | 내 메일 주소 |
  | `APP_IMAGE` | 11단계 이미지 이름 |
  | `DB_NAME`, `DB_USER` | 예: `rentkeeper`, `rentkeeper` |
  | `APP_BASE_URL` | `https://<도메인>` (끝에 `/` 없이) |
  | `APP_ENCRYPTION_KEY_VERSION` | `1` |
  | `KAKAO_*` | 10단계 값 |
  | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | 8단계 값 (`MAIL_PASSWORD`는 SMTP 키) |
  | `MAIL_FROM` | `RentKeeper <no-reply@<도메인>>` |
  | `APP_DEMO_ENABLED` | `true` |
  | `BACKUP_*` | 9단계 값과 위에서 만든 암호 |
  | `APP_NOTIFICATION_TENANT_PHONE_ENABLED` | `false` 그대로 |

- [ ] **0번 원칙**: `APP_ENCRYPTION_KEY`, `APP_HMAC_KEY`, `BACKUP_PASSPHRASE`를 비밀번호 관리자 + 오프라인 사본 두 곳에 저장했는지 한 번 더 확인한다. 잃으면 복구할 수 없다

## 13. 첫 배포
서버 `~/rentkeeper`에서:
- [ ] 이미지 저장소 로그인(토큰은 화면에 보이지 않게 입력):
  ```bash
  read -rs GHCR_TOKEN && echo "$GHCR_TOKEN" | docker login ghcr.io -u <GitHub 계정> --password-stdin; unset GHCR_TOKEN
  chmod 600 ~/.docker/config.json
  ```
- [ ] 줄여 쓰기: `C="docker compose --env-file .env.prod -f docker/compose.prod.yml"`
- [ ] Caddy 설정 확인: `$C run --rm caddy caddy validate --config /etc/caddy/Caddyfile` → `Valid configuration`
- [ ] 받기: `$C pull`
- [ ] 띄우기: `$C up -d`
- [ ] 1~2분 뒤 `$C ps` → 세 개 모두 `Up`, app은 `(healthy)`
- [ ] `$C logs --tail 100 app` → `Successfully applied 22 migrations`, `Started RentKeeperApplication`. 안 뜨면 `IllegalStateException` 문구가 빠진 값을 알려 준다(runbook 10)
- [ ] 브라우저로 `https://<도메인>` → 첫 화면과 자물쇠 표시(HTTPS)

## 14. 백업 켜기와 첫 복구 연습
- [ ] 한 번 손으로: `scripts/backup.sh` → `backup: rentkeeper-…dump.gpg (…) 완료`, R2 버킷에 파일이 생겼다
- [ ] 매일 자동: `crontab -e` → 맨 아래에 `30 3 * * * cd /home/ubuntu/rentkeeper && scripts/backup.sh >> /home/ubuntu/backups/backup.log 2>&1`
- [ ] 복구 연습: runbook 7을 내 PC에서 한 번 해 본다. 걸린 시간: `____`

## 15. 배포 직후 확인 (기능·보안)
progress.md "배포 직전 사람 확인 목록"의 M6 항목과 같다. 결과를 거기에 적는다.
- [ ] HTTPS·HSTS·로그인 쿠키 Secure
- [ ] IP별 레이트 리미트(두 곳에서 접속), 세입자 링크 한도 포함
- [ ] Caddy 로그에서 세입자 링크 토큰이 가려짐(`/t/***`, Referer 포함)
- [ ] 세입자 [입금했어요]·[안내 문자 그만 받기] 실제 클릭(403이 나오지 않음)
- [ ] 카카오 로그인(초대 코드 필요)·탈퇴, 실제 메일 수신(가입 인증·임대인 요약, 스팸함 포함)
- [ ] 첫 화면 [데모로 둘러보기] → 데모 띠, 세입자 링크, 설정 변경 막힘
- [ ] 초대 코드 없이 가입이 안 된다, 초대 코드로 내 계정 가입
- [ ] 시크릿 창 IDOR(A-7), 유효한 세입자 링크의 Set-Cookie 없음(D-46), F5 다시 보내기(D-44), 클립보드 내용
- [ ] 백업·복구 연습(14단계)
- [ ] 로그인 한 번 걸리는 시간(1초 넘으면 알린다), 대시보드가 뜨는 시간

## 16. 그다음
- 디자인 교체 → 디자인 뒤 화면·문구 확인(휴대폰 폭·글자 200%·머리글·문구 읽기, progress.md 목록) → README·캡처.
- 저장소를 공개로 바꿀 때: 이미지도 공개로 바꾸고 서버에서 `docker logout ghcr.io` (runbook 3).
