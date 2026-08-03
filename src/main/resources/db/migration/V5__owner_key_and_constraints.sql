-- jobit-front 와 스키마를 맞춘다 (2026-08-03 결정: 인증은 프론트, 도메인은 이 서버).
--
-- 핵심은 jd_submission 의 소유자 표현이다. 인증이 프론트(Auth.js)에 남으면 회원 행은
-- 프론트의 user 테이블에 생기고 이 서버의 member 테이블에는 아무것도 들어오지 않는다.
-- member_id FK 를 그대로 두면 채울 값이 없다.


-- ─── jd_submission: member_id → owner_key ────────────────────────────────────
--
-- 규약은 프론트와 동일하다 (스펙 §3.3, resume.owner_key 와 같은 값 공간):
--   로그인   'user:<user_id>'
--   비로그인 'anon:<세션 쿠키>'
--
-- 접두사를 붙이는 이유: 회원 ID 와 익명 세션 키가 한 컬럼을 공유하므로, 접두사가 없으면
-- 두 네임스페이스가 충돌할 수 있다. 조회 쿼리는 owner_key 하나만 보면 되고
-- 로그인 여부로 분기하지 않는다.

ALTER TABLE jd_submission
    ADD COLUMN owner_key text;

-- 기존 행은 전부 회원 소유였다.
UPDATE jd_submission
SET owner_key = 'user:' || member_id::text
WHERE owner_key IS NULL;

ALTER TABLE jd_submission
    ALTER COLUMN owner_key SET NOT NULL;

ALTER TABLE jd_submission
    DROP CONSTRAINT jd_submission_unique;

DROP INDEX IF EXISTS idx_jd_submission_member;

ALTER TABLE jd_submission
    DROP COLUMN member_id;

-- 같은 사람이 같은 공고를 여러 번 넣어도 목록에는 한 줄만 (스펙 §3.6).
ALTER TABLE jd_submission
    ADD CONSTRAINT jd_submission_owner_unique UNIQUE (owner_key, job_posting_id);

-- 목록 조회: 내 것만, 최근순.
CREATE INDEX idx_jd_submission_owner ON jd_submission (owner_key, updated_at DESC);


-- ─── question_set: 중복 생성 방어 ────────────────────────────────────────────
--
-- 스펙 §4.2 "prompt_version 이 같으면 재생성하지 않는다"는 조회만으로는 지켜지지 않는다.
-- 동시 요청 둘이 나란히 캐시 미스로 판단하면 같은 공고에 세트가 두 개 생긴다.
-- 프론트는 이 유니크 제약을 최종 방어선으로 쓰고 있는데 이쪽에는 없었다.
-- 유니크 인덱스가 기존 조회 인덱스를 대체하므로 그쪽은 지운다.

DROP INDEX IF EXISTS idx_question_set_job_posting;

ALTER TABLE question_set
    ADD CONSTRAINT question_set_posting_version_unique UNIQUE (job_posting_id, prompt_version);


-- ─── llm_call_log: 프롬프트 캐시 토큰 ────────────────────────────────────────
--
-- 프롬프트 캐시는 읽기와 생성의 단가가 다르므로 입력 토큰에 합산하면 비용이 틀어진다.
-- 프론트는 이미 두 컬럼을 나눠 기록하고 있다.

ALTER TABLE llm_call_log
    ADD COLUMN cache_read_tokens int NOT NULL DEFAULT 0;

ALTER TABLE llm_call_log
    ADD COLUMN cache_creation_tokens int NOT NULL DEFAULT 0;


-- ─── job_posting.parsed: NOT NULL ────────────────────────────────────────────
--
-- content_hash 캐시는 "한 번 저장되면 계속 재사용된다"는 뜻이라, 파싱 결과가 빠진 행이
-- 들어가면 그 상태로 굳는다. JdParserFallbackConfig 가 빈 결과 대신 예외를 던지는 것과 같은 이유다.
-- 기존 행이 있다면 빈 객체로 채우되, 그런 행은 원래 남으면 안 되는 것이므로 확인이 필요하다.

UPDATE job_posting
SET parsed = '{}'::jsonb
WHERE parsed IS NULL;

ALTER TABLE job_posting
    ALTER COLUMN parsed SET NOT NULL;


-- ─── member: 당분간 미사용 ───────────────────────────────────────────────────

COMMENT ON TABLE member IS
    '인증이 jobit-front(Auth.js)에 있는 동안 사용되지 않는다. 회원 행은 프론트의 user 테이블에 있다. docs/architecture.md 참고.';

COMMENT ON TABLE password_reset_token IS
    '인증이 jobit-front 에 있는 동안 사용되지 않는다. docs/architecture.md 참고.';
