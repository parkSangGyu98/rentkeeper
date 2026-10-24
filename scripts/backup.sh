#!/usr/bin/env bash
# 운영 DB 백업 (M6 결정 7, docs/runbook.md "백업"). 서버의 cron이 매일 03:30에 부른다.
#   1) postgres 컨테이너 안에서 pg_dump (custom 형식)
#   2) gpg 대칭 암호화 (BACKUP_PASSPHRASE)
#   3) 서버에 7일치를 두고, S3 호환 저장소(Cloudflare R2)에 올린다 (aws-cli 컨테이너, 서버에 따로 설치하지 않는다)
# 사용: scripts/backup.sh [저장소 폴더]   (기본: 이 스크립트가 있는 저장소의 상위 폴더 기준 ../backups)
# 값은 저장소 루트의 .env.prod에서 필요한 이름만 읽는다. 값이나 암호를 화면·로그에 찍지 않는다.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$REPO_DIR/.env.prod"
BACKUP_DIR="${1:-$REPO_DIR/../backups}"
KEEP_DAYS=7

# .env.prod에서 한 줄만 꺼낸다 (source 하지 않는다: 값에 공백·꺾쇠가 있어도 안전하게)
env_value() {
	grep -E "^$1=" "$ENV_FILE" | head -n 1 | cut -d= -f2-
}

for name in DB_NAME DB_USER BACKUP_PASSPHRASE BACKUP_S3_ENDPOINT BACKUP_S3_BUCKET BACKUP_S3_ACCESS_KEY BACKUP_S3_SECRET_KEY; do
	if [ -z "$(env_value "$name")" ]; then
		echo "backup: $name 값이 .env.prod에 없습니다" >&2
		exit 1
	fi
done

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
STAMP="$(TZ=Asia/Seoul date +%Y%m%d-%H%M)"
FILE="$BACKUP_DIR/rentkeeper-$STAMP.dump.gpg"

# 1·2) 덤프를 디스크에 평문으로 남기지 않고 바로 암호화한다. 암호는 파일 디스크립터 3으로만 넘긴다
docker compose --env-file "$ENV_FILE" -f "$REPO_DIR/docker/compose.prod.yml" exec -T postgres \
	pg_dump -U "$(env_value DB_USER)" -d "$(env_value DB_NAME)" -Fc \
	| gpg --batch --yes --symmetric --cipher-algo AES256 --pinentry-mode loopback --passphrase-fd 3 \
		--output "$FILE" 3< <(printf '%s' "$(env_value BACKUP_PASSPHRASE)")
chmod 600 "$FILE"

# 3) 저장소에 올린다
docker run --rm \
	-e AWS_ACCESS_KEY_ID="$(env_value BACKUP_S3_ACCESS_KEY)" \
	-e AWS_SECRET_ACCESS_KEY="$(env_value BACKUP_S3_SECRET_KEY)" \
	-e AWS_DEFAULT_REGION=auto \
	-v "$BACKUP_DIR:/backup:ro" \
	amazon/aws-cli s3 cp "/backup/$(basename "$FILE")" "s3://$(env_value BACKUP_S3_BUCKET)/" \
	--endpoint-url "$(env_value BACKUP_S3_ENDPOINT)" --only-show-errors

# 서버에는 최근 7일치만 둔다 (저장소 쪽 보관 기간은 버킷의 수명 주기 규칙으로 정한다)
find "$BACKUP_DIR" -name 'rentkeeper-*.dump.gpg' -mtime +"$KEEP_DAYS" -delete

echo "backup: $(basename "$FILE") ($(du -h "$FILE" | cut -f1)) 완료"
