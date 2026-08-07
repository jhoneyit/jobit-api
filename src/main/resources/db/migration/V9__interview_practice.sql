-- 면접 연습 (음성) — docs/interview-practice-design.md
--
-- 공고의 예상 질문을 소리 내어 답하고, question.answer_outline(답변 뼈대)과 얼마나 맞는지
-- 점수로 확인한다. **채점 기준을 새로 만들지 않는다** — 사용자가 결과 화면에서 본 그 뼈대가
-- 그대로 기준이다. 모범 답안 생성을 위한 LLM 호출이 따로 필요 없고, 화면에 보여준 것과
-- 채점 기준이 어긋나지 않는다.
--
-- **오디오는 저장하지 않는다.** STT 는 브라우저(Web Speech API)가 하고 서버는 텍스트만 받는다.
-- 그래서 이 마이그레이션에는 오디오 컬럼도, 그에 딸린 암호화·삭제 경로도 없다.

-- 한 번의 연습 = 한 세션.
CREATE TABLE interview_session (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- 'user:<id>' 또는 'anon:<쿠키>' (common.OwnerKey 규약). 비로그인도 연습할 수 있다.
    owner_key       text        NOT NULL,

    job_posting_id  uuid        NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,

    -- **어느 질문 세트로 봤는지를 세션에 박아 둔다.** prompt_version 이 올라가면 같은 공고라도
    -- 질문이 바뀌는데, 기록에는 "그때 그 질문"이 남아야 한다. job_posting_id 만으로는
    -- 나중에 상세 화면을 열 때 다른 질문이 딸려 온다.
    question_set_id uuid        NOT NULL REFERENCES question_set (id) ON DELETE CASCADE,

    -- 이 세션에 출제된 문항 수. 설정(questions-per-session)이 바뀌어도 과거 총점의 분모는
    -- 그대로여야 하므로 값을 남긴다.
    question_count  smallint    NOT NULL,
    answered_count  smallint    NOT NULL DEFAULT 0,

    -- 0~100. 종료 전이면 null — 진행 중인 세션에 점수를 붙이면 "아직 못 푼 것"과
    -- "풀었는데 0점"이 구분되지 않는다.
    total_score     smallint,

    started_at      timestamptz NOT NULL DEFAULT now(),
    -- null = 진행 중이거나 중간에 이탈했다. 둘을 구분하지 않는다 — 어느 쪽이든 미완이다.
    finished_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT interview_session_question_count_positive CHECK (question_count > 0),
    CONSTRAINT interview_session_answered_range
        CHECK (answered_count BETWEEN 0 AND question_count),
    CONSTRAINT interview_session_total_score_range
        CHECK (total_score IS NULL OR total_score BETWEEN 0 AND 100)
);

-- 기록 목록은 소유자별 최근순이다 (/profile/interviews).
CREATE INDEX idx_interview_session_owner ON interview_session (owner_key, started_at DESC);

COMMENT ON TABLE interview_session IS
    '총점은 답한 문항의 평균이 아니라 출제된 전 문항의 평균이다 — 답한 것만 평균 내면 '
    '한 문항만 답하고 나가는 쪽이 유리해진다. 미답변은 0점으로 센다.';


-- 문항 하나에 대한 답변과 채점 결과.
CREATE TABLE interview_answer (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id     uuid        NOT NULL REFERENCES interview_session (id) ON DELETE CASCADE,
    question_id    uuid        NOT NULL REFERENCES question (id) ON DELETE CASCADE,

    -- 출제 순서 = 표시 순서. question.sort_order 를 그대로 따르지만, 나중에 문항을 섞게 되면
    -- 둘이 갈라지므로 세션 기준 순서를 따로 남긴다.
    sort_order     smallint    NOT NULL,

    -- STT 결과. **null 은 "시간 내에 답하지 못했다"는 정상 경로다** — 그 자체가 결과다.
    -- 개인 발화라 이력서에 준해 다룬다: 로그에 남기지 않고 아래 TTL 로 지운다.
    transcript     text,

    duration_ms    int         NOT NULL,

    -- **그때의 제한 시간을 행마다 남긴다.** 설정을 60초에서 90초로 바꿔도 과거 기록의 의미가
    -- 변하면 안 된다. 설정값을 참조만 하면 과거 점수의 근거가 소급해서 바뀐다.
    time_limit_sec smallint    NOT NULL,

    -- 0~100. 채점 전이면 null.
    score          smallint,

    -- **answer_outline 의 인덱스 배열이다** (예: [0,2]). 뼈대 문구를 복사해 두지 않는 이유는
    -- 같은 문장이 두 군데 살게 되고, 화면은 어차피 뼈대를 나란히 보여줘야 하기 때문이다.
    -- 모델이 범위 밖 인덱스를 지어낼 수 있으므로 저장 전에 서버가 재검증한다.
    covered        jsonb,
    missed         jsonb,

    -- 한 줄 피드백. **모범 답변을 대신 써주지 않는다** (스펙 §4.5 "지어내지 않는다"의 적용) —
    -- 답을 써 주면 다음 연습에서 그걸 외워 말하게 되고 점수만 오른다.
    feedback       text,

    scored_at      timestamptz,

    -- transcript 만의 TTL. 만료되면 transcript 를 null 로 지우고 점수·covered/missed·feedback 은
    -- 남긴다. 기록 전체를 지우면 "내 면접 기록" 기능이 죽고, 발화 원문 없이도 점수 추이는 읽힌다.
    transcript_expires_at timestamptz,

    created_at     timestamptz NOT NULL DEFAULT now(),

    -- 한 세션에서 한 질문은 한 번. 마이크가 안 잡혔을 때 다시 제출하면 덮어쓴다.
    -- 이게 없으면 같은 질문의 점수가 여러 개 남아 총점이 흔들린다.
    CONSTRAINT interview_answer_unique_question UNIQUE (session_id, question_id),

    CONSTRAINT interview_answer_score_range
        CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT interview_answer_duration_non_negative CHECK (duration_ms >= 0),
    CONSTRAINT interview_answer_time_limit_positive CHECK (time_limit_sec > 0),
    -- covered/missed 는 반드시 배열이어야 한다. 객체나 문자열이 들어오면 읽는 쪽이 조용히 깨진다
    -- (user_profile.stacks 와 같은 이유).
    CONSTRAINT interview_answer_covered_is_array
        CHECK (covered IS NULL OR jsonb_typeof(covered) = 'array'),
    CONSTRAINT interview_answer_missed_is_array
        CHECK (missed IS NULL OR jsonb_typeof(missed) = 'array')
);

-- 세션 상세 화면은 한 세션의 답변을 출제 순서대로 읽는다.
CREATE INDEX idx_interview_answer_session ON interview_answer (session_id, sort_order);

-- transcript 만료 정리용. 조회는 session_id 로 하므로 이 인덱스는 정리 작업 전용이다.
-- 이미 지운 행은 다시 볼 필요가 없어 부분 인덱스로 둔다.
CREATE INDEX idx_interview_answer_transcript_expiry
    ON interview_answer (transcript_expires_at)
    WHERE transcript IS NOT NULL;

COMMENT ON TABLE interview_answer IS
    '오디오는 어디에도 저장하지 않는다 — STT 는 브라우저가 하고 서버는 transcript 만 받는다. '
    'transcript 는 사용자가 자기 경력을 말한 내용이라 이력서에 준해 다룬다 (스펙 §6).';
