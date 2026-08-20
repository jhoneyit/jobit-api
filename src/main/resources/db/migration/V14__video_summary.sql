-- 영상 요약 (스펙 외 새 축 — 면접 연습과 같은 성격의 확장).
--
-- job_posting 과 같은 이원 구조다: video_summary 는 video_id 기준 **전역 캐시**(같은 영상을
-- 두 사람이 넣으면 한 번만 처리), video_submission 이 소유자별 이력이다.
--
-- **자막 원문은 저장하지 않는다.** 보고서만 남긴다 — 자막은 언제든 다시 받을 수 있고,
-- 1시간 영상 자막은 수십 KB 라 쌓이면 보고서보다 훨씬 무겁다.
CREATE TABLE video_summary (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id          text        NOT NULL UNIQUE,  -- 유튜브 11자 ID = 전역 캐시 키
    url               text        NOT NULL,
    title             text,
    channel           text,
    duration_sec      int,
    -- CAPTION = 유튜브 자막(수 초), STT = Whisper 전사(영상 길이만큼 느리다). 완료 후 채운다.
    transcript_source varchar(10),
    -- 처리가 수 분~수십 분이라 동기 응답이 불가능하다. 폴링이 이 컬럼을 읽는다.
    status            varchar(10) NOT NULL DEFAULT 'PENDING',
    error_message     text,
    report            jsonb,
    prompt_version    text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT video_summary_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT video_summary_source_check
        CHECK (transcript_source IS NULL OR transcript_source IN ('CAPTION', 'STT'))
);

CREATE TABLE video_submission (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_key        text        NOT NULL,
    video_summary_id uuid        NOT NULL REFERENCES video_summary (id) ON DELETE CASCADE,
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- 같은 사람이 같은 영상을 다시 넣어도 줄을 늘리지 않는다 (jd_submission 과 같은 규약).
    CONSTRAINT video_submission_unique UNIQUE (owner_key, video_summary_id)
);
CREATE INDEX idx_video_submission_owner ON video_submission (owner_key, created_at DESC);
