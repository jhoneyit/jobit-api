-- 스펙 §3 데이터 모델.
-- enum은 Postgres 네이티브 타입 대신 varchar + CHECK로 둔다.
-- 값 추가 시 ALTER TYPE 없이 마이그레이션 한 줄로 끝나고, Hibernate 매핑도 단순해진다.

-- §3.6 회원
CREATE TABLE member (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider     varchar(20)  NOT NULL,
    provider_uid text         NOT NULL,
    email        text,
    nickname     text         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT member_provider_check CHECK (provider IN ('GITHUB')),
    CONSTRAINT member_provider_uid_unique UNIQUE (provider, provider_uid)
);

-- §3.1 공고 — content_hash 기준 전역 캐시. 소유자를 붙이지 않는다.
CREATE TABLE job_posting (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content_hash text        NOT NULL UNIQUE,
    raw_text     text        NOT NULL,
    source_url   text,
    company      text,
    title        text,
    parsed       jsonb,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- §3.1 요구사항 — 질문·갭분석 공통 앵커
CREATE TABLE requirement (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_posting_id uuid   NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    text           text   NOT NULL,
    kind           varchar(20) NOT NULL,
    keywords       text[] NOT NULL DEFAULT '{}',
    sort_order     int    NOT NULL DEFAULT 0,
    CONSTRAINT requirement_kind_check CHECK (kind IN ('REQUIRED', 'PREFERRED', 'RESPONSIBILITY'))
);
CREATE INDEX idx_requirement_job_posting ON requirement (job_posting_id, sort_order);

-- §3.2 면접 질문
CREATE TABLE question_set (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_posting_id uuid        NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    prompt_version text        NOT NULL,
    model          text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_question_set_job_posting ON question_set (job_posting_id);

CREATE TABLE question (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    question_set_id uuid     NOT NULL REFERENCES question_set (id) ON DELETE CASCADE,
    requirement_id  uuid     REFERENCES requirement (id) ON DELETE SET NULL,
    text            text     NOT NULL,
    category        varchar(20) NOT NULL,
    difficulty      smallint NOT NULL,
    followups       jsonb,
    answer_outline  jsonb,
    CONSTRAINT question_category_check
        CHECK (category IN ('CS', 'STACK', 'EXPERIENCE', 'DESIGN', 'CULTURE'))
);
CREATE INDEX idx_question_set ON question (question_set_id);

-- §3.3 이력서 — owner_key는 익명 세션 키 또는 member.id
CREATE TABLE resume (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_key  text        NOT NULL,
    raw_text   text        NOT NULL,
    parsed     jsonb,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_resume_owner ON resume (owner_key);
CREATE INDEX idx_resume_expires ON resume (expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE resume_bullet (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id  uuid NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    company    text,
    period     text,
    text       text NOT NULL,
    embedding  vector(1536),
    sort_order int  NOT NULL DEFAULT 0
);
CREATE INDEX idx_resume_bullet_resume ON resume_bullet (resume_id, sort_order);

-- §3.4 갭 분석 · 첨삭
CREATE TABLE gap_analysis (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id      uuid        NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    job_posting_id uuid        NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT gap_analysis_unique UNIQUE (resume_id, job_posting_id)
);

CREATE TABLE gap_item (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    gap_analysis_id    uuid NOT NULL REFERENCES gap_analysis (id) ON DELETE CASCADE,
    requirement_id     uuid NOT NULL REFERENCES requirement (id) ON DELETE CASCADE,
    status             varchar(10) NOT NULL,
    evidence_bullet_id uuid REFERENCES resume_bullet (id) ON DELETE SET NULL,
    rationale          text NOT NULL,
    CONSTRAINT gap_item_status_check CHECK (status IN ('MET', 'WEAK', 'MISSING'))
);
CREATE INDEX idx_gap_item_analysis ON gap_item (gap_analysis_id);

CREATE TABLE rewrite_suggestion (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    gap_item_id uuid    NOT NULL REFERENCES gap_item (id) ON DELETE CASCADE,
    bullet_id   uuid    NOT NULL REFERENCES resume_bullet (id) ON DELETE CASCADE,
    original    text    NOT NULL,
    suggested   text    NOT NULL,
    reason      text    NOT NULL,
    accepted    boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_rewrite_gap_item ON rewrite_suggestion (gap_item_id);

-- §3.6 입력 이력. 같은 공고 재입력 시 행을 늘리지 않고 updated_at을 갱신한다.
CREATE TABLE jd_submission (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id      uuid        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    job_posting_id uuid        NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    memo           text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT jd_submission_unique UNIQUE (member_id, job_posting_id)
);
CREATE INDEX idx_jd_submission_member ON jd_submission (member_id, updated_at DESC);

-- §3.5 비용 추적
CREATE TABLE llm_call_log (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    feature       varchar(40) NOT NULL,
    model         text        NOT NULL,
    input_tokens  int         NOT NULL,
    output_tokens int         NOT NULL,
    cost_usd      numeric(12, 6) NOT NULL,
    cache_hit     boolean     NOT NULL DEFAULT false,
    latency_ms    int         NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_call_log_created ON llm_call_log (created_at DESC);
