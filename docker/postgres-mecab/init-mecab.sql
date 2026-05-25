-- v0.75.0 — Phase 58 #8. mecab-ko PG 함수 정의.
--
-- Existing DB 에서는 자동 실행 안 됨 (docker-entrypoint-initdb.d 는 첫 부팅 only).
-- 기존 PG 에 적용하려면:
--   docker exec -i vibe-coder-postgres psql -U vibecoder -d vibecoder < init-mecab.sql

-- 1. plpython3u extension 활성화 (untrusted — superuser only).
CREATE EXTENSION IF NOT EXISTS plpython3u;

-- 2. mecab tokenize 함수. python-mecab 바인딩 사용.
--    한국어 텍스트 → 명사/동사/형용사 stem 만 추출 (어미 제거).
--    빈 입력 / NULL → 빈 배열.
CREATE OR REPLACE FUNCTION mecab_kor_tokens(t text) RETURNS text[]
LANGUAGE plpython3u IMMUTABLE STRICT
AS $$
    if not t or not t.strip():
        return []
    try:
        import MeCab
    except ImportError:
        return []  # mecab Python binding 미설치 — graceful empty.
    # cache parser in shared dict (per backend connection).
    parser = GD.get('mecab_parser')
    if parser is None:
        parser = MeCab.Tagger("-d /var/lib/mecab/dic/mecab-ko-dic")
        GD['mecab_parser'] = parser
    node = parser.parseToNode(t)
    tokens = []
    while node:
        if node.stat in (0, 1):  # normal / unknown — skip BOS/EOS.
            features = node.feature.split(",")
            pos = features[0] if features else ""
            # 명사 / 동사 / 형용사 stem 만 (조사/어미 제거).
            if pos and pos[0] in ("N", "V") or pos == "MM" or pos == "MAG":
                # mecab-ko 의 lemma 는 features[7] 또는 surface fallback.
                lemma = features[7] if len(features) > 7 and features[7] != "*" else node.surface
                if lemma and lemma.strip():
                    tokens.append(lemma.strip().lower())
        node = node.next
    return tokens
$$;

-- 3. 검색 편의 함수: query string → token array (같은 tokenizer 사용 → 일관).
CREATE OR REPLACE FUNCTION mecab_kor_query(q text) RETURNS text[]
LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT mecab_kor_tokens(q);
$$;

-- 4. (선택) generated column — application layer 통합 시 사용.
--    초기엔 정의 안 함 — 사용자가 수동으로 실행:
--      ALTER TABLE conversation_turns
--          ADD COLUMN mecab_tokens text[]
--          GENERATED ALWAYS AS (mecab_kor_tokens(content)) STORED;
--      CREATE INDEX conversation_turns_mecab_idx
--          ON conversation_turns USING gin(mecab_tokens);
--    NOT NULL 컬럼이 아니므로 추가는 즉시. 큰 테이블은 backfill 시간 소요.

-- 5. 권한 — 일반 user 가 호출 가능하도록.
GRANT EXECUTE ON FUNCTION mecab_kor_tokens(text) TO PUBLIC;
GRANT EXECUTE ON FUNCTION mecab_kor_query(text) TO PUBLIC;
