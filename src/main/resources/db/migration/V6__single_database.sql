-- DB 단일화 (2026-08-04 결정).
--
-- 그동안 프론트는 자기 DB(jobit-pg :55432)를, 이 서버는 이 DB(:5432)를 따로 썼다.
-- 같은 테이블이 양쪽에 있었고 서로를 몰랐다. 이제 이 DB 하나로 합친다.
--
-- **스키마 소유권은 Flyway 단일이다.** 한 DB에 마이그레이션 도구가 둘이면 반드시 어긋나므로
-- `drizzle-kit` 은 마이그레이션에서 손을 뗀다 (jobit-front/drizzle.config.ts 주석 참고).
-- 프론트의 Drizzle 스키마는 이제 **이 파일을 따라오는 타입 선언**일 뿐이다.
--
-- 옮길 데이터는 없었다 — 양쪽 DB 모두 회원 1행 외에는 비어 있었다. 그래서 이관 SQL 대신
-- 지우고 다시 세운다.


-- ─── 1. 인증: member → Auth.js ──────────────────────────────────────────────
--
-- 2026-08-03 에 "인증은 프론트(Auth.js)에 남긴다"로 확정했다. 그러면 회원 행은 Auth.js 가
-- 관리하는 user 테이블에 생기고 member 에는 영원히 아무것도 들어오지 않는다.
-- V5 에서 주석으로 "당분간 미사용"이라 적어 뒀던 것을 이제 정리한다.
--
-- password_reset_token 을 먼저 지운다 — member 를 참조하고 있어서 순서가 뒤바뀌면 실패한다.

DROP TABLE IF EXISTS password_reset_token;
DROP TABLE IF EXISTS member;

-- Auth.js(@auth/drizzle-adapter) 표준 테이블.
--
-- **식별자를 큰따옴표로 감싼 것은 실수가 아니다.** Auth.js 어댑터가 camelCase 컬럼명을
-- 그대로 쓰므로, 따옴표 없이 쓰면 Postgres 가 소문자로 접어 어댑터가 컬럼을 못 찾는다.
-- 컬럼 구성은 jobit-front/drizzle/ 의 생성 DDL 과 정확히 같아야 한다.
--
-- id 가 uuid 가 아니라 text 인 것도 어댑터 규약이다 (crypto.randomUUID() 문자열).

CREATE TABLE "user" (
    "id"            text PRIMARY KEY,
    "name"          text,
    "email"         text UNIQUE,
    "emailVerified" timestamptz,
    "image"         text,
    -- 이메일+비밀번호 가입자만 값이 있다. GitHub 로만 가입하면 null.
    -- 형식은 scrypt$N$r$p$salt$hash — jobit-front/src/lib/auth/password.ts 참고.
    -- 이 서버는 이 값을 읽지 않는다. 인증은 프론트가 한다.
    "password_hash" text
);

CREATE TABLE "account" (
    "userId"            text NOT NULL REFERENCES "user" ("id") ON DELETE CASCADE,
    "type"              text NOT NULL,
    "provider"          text NOT NULL,
    "providerAccountId" text NOT NULL,
    "refresh_token"     text,
    "access_token"      text,
    "expires_at"        integer,
    "token_type"        text,
    "scope"             text,
    "id_token"          text,
    "session_state"     text,
    PRIMARY KEY ("provider", "providerAccountId")
);

CREATE TABLE "session" (
    "sessionToken" text PRIMARY KEY,
    "userId"       text        NOT NULL REFERENCES "user" ("id") ON DELETE CASCADE,
    "expires"      timestamptz NOT NULL
);

CREATE TABLE "verificationToken" (
    "identifier" text        NOT NULL,
    "token"      text        NOT NULL,
    "expires"    timestamptz NOT NULL,
    PRIMARY KEY ("identifier", "token")
);

-- 비밀번호 재설정 토큰 — 이번에는 user 를 참조한다.
-- 원문이 아니라 SHA-256 해시를 저장한다: DB 가 유출돼도 그 값으로는 재설정할 수 없다.
CREATE TABLE password_reset_token (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash text        NOT NULL UNIQUE,
    user_id    text        NOT NULL REFERENCES "user" ("id") ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX password_reset_user_idx ON password_reset_token (user_id, created_at);


-- ─── 2. question: 프론트가 쓰던 컬럼 흡수 ───────────────────────────────────
--
-- 질문은 생성된 순서가 곧 표시 순서다. 프론트 스키마에는 있었는데 이쪽에 없었다 —
-- 이게 없으면 조회 때마다 순서가 뒤바뀐다.

ALTER TABLE question
    ADD COLUMN sort_order int NOT NULL DEFAULT 0;

DROP INDEX IF EXISTS idx_question_set;
CREATE INDEX idx_question_set ON question (question_set_id, sort_order);

-- 꼬리질문·답변뼈대는 "없으면 빈 배열"이지 "없으면 null"이 아니다.
-- null 을 허용하면 읽는 쪽마다 null 체크를 해야 하고, 한 곳만 빠뜨려도 터진다.
UPDATE question SET followups = '[]'::jsonb WHERE followups IS NULL;
UPDATE question SET answer_outline = '[]'::jsonb WHERE answer_outline IS NULL;

ALTER TABLE question
    ALTER COLUMN followups SET DEFAULT '[]'::jsonb,
    ALTER COLUMN followups SET NOT NULL,
    ALTER COLUMN answer_outline SET DEFAULT '[]'::jsonb,
    ALTER COLUMN answer_outline SET NOT NULL;


-- ─── 3. jd_submission: 프론트 조회 경로용 인덱스 ────────────────────────────
--
-- 이쪽은 (owner_key, updated_at DESC) 로 목록을 뽑았고 프론트는 created_at 을 썼다.
-- 두 경로가 다 남을 수 있으므로 인덱스도 둘 다 둔다. 행이 적어 비용은 무시할 만하다.

CREATE INDEX IF NOT EXISTS jd_submission_owner_created_idx
    ON jd_submission (owner_key, created_at DESC);


-- ─── 4. 프론트가 만들던 enum 타입은 만들지 않는다 ───────────────────────────
--
-- 프론트 Drizzle 은 requirement_kind / question_category / llm_feature 를 Postgres 네이티브
-- enum 으로 선언했지만, 이 서버는 V2 부터 varchar + CHECK 로 두고 있다.
-- 값을 추가할 때 ALTER TYPE 없이 마이그레이션 한 줄로 끝나고 Hibernate 매핑도 단순하다.
--
-- **스키마 소유권이 Flyway 로 넘어왔으므로 varchar + CHECK 가 정본이다.**
-- 프론트의 Drizzle 선언을 pgEnum → varchar 로 바꿔야 한다 (그쪽 schema.ts 주석 참고).
