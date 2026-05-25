# syntax=docker/dockerfile:1.7
#
# v0.75.0 — Phase 58 #8. mecab-ko 통합 PostgreSQL 이미지.
#
# 사용법 (docker compose 의 postgres service 의 image 를 override):
#   docker build -f docker/postgres-mecab/Dockerfile -t siamakerlab/postgres-mecab-ko:17 .
#
# 또는 docker compose 의 .env:
#   VIBECODER_POSTGRES_IMAGE=siamakerlab/postgres-mecab-ko:17
#
# 그 후 `docker compose up -d postgres` 하면 mecab 활성화된 PG 가 뜸.
#
# Architecture:
#   - base: postgres:17 (Debian — Alpine 은 mecab-ko-dic apt 패키지 없음)
#   - mecab + mecab-ko-dic apt install
#   - plpython3u extension + python mecab 바인딩 → SQL 함수
#     `mecab_kor_tokens(text) RETURNS text[]` 노출
#
# 이 함수가 있으면 application (Kotlin) 측에서 다음과 같이 검색 가능:
#   SELECT * FROM conversation_turns
#   WHERE mecab_kor_tokens(content) && mecab_kor_tokens(?);  -- array overlap
#
# 또는 generated column 추가 후 GIN 인덱스:
#   ALTER TABLE conversation_turns ADD COLUMN mecab_tokens text[]
#     GENERATED ALWAYS AS (mecab_kor_tokens(content)) STORED;
#   CREATE INDEX ON conversation_turns USING gin(mecab_tokens);
#
# 본 v0.75.0 은 image + 함수만 — application 측 통합은 환경 변수 후속 cycle.

FROM postgres:17

ENV DEBIAN_FRONTEND=noninteractive

# mecab + mecab-ko-dic (Debian/Ubuntu universe).
RUN apt-get update && apt-get install -y --no-install-recommends \
        mecab \
        libmecab-dev \
        mecab-ko-dic \
        python3 python3-pip python3-mecab \
        postgresql-plpython3-17 \
    && rm -rf /var/lib/apt/lists/*

# mecab-ko-dic 이 /var/lib/mecab/dic/mecab-ko-dic 에 설치됨 (apt 표준 경로).
# /etc/mecabrc 가 자동으로 ko-dic 사용하도록 dicdir 변경.
RUN sed -i 's|^dicdir.*|dicdir = /var/lib/mecab/dic/mecab-ko-dic|' /etc/mecabrc || true

# 초기 init script — PG 가 첫 부팅 시 자동 실행 (docker-entrypoint-initdb.d).
# Existing DB 가 있으면 실행 안 됨 (init dir 표준 동작) — manual 실행 필요.
COPY init-mecab.sql /docker-entrypoint-initdb.d/01-init-mecab.sql
