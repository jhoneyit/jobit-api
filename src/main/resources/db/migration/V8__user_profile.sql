-- 내 정보(프로필) — 공고가 알 수 없는 지원자 본인의 정보.
--
-- 파싱은 공고에서 { 스택, 요구 연차, 도메인 }을 뽑지만 그건 전부 "공고가 원하는 것"이다.
-- "내가 실제로 다뤄본 스택"과 "내 실제 연차"는 어디에도 없고, 이 둘이 있어야 요구사항과의
-- 교집합/차집합을 낼 수 있다. 직무·관심분야는 일부러 받지 않는다 — 공고에서 이미 나오는
-- 정보라 프로필에 또 두면 두 곳이 서로 다른 말을 하게 된다.
--
-- **이 테이블은 당분간 프론트만 읽고 쓴다.** 질문 생성 LLM 호출에는 넣지 않기로 했다
-- (넣으면 question_set 의 캐시 키에 프로필이 붙어 인기 공고도 매번 재생성된다).
-- 프로필은 이미 생성된 질문을 정렬·강조하는 표시 단계에서만 쓰인다.
-- 그래서 대응하는 JPA 엔티티를 만들지 않는다 — 3단계 갭 분석이 이 값을 baseline 으로
-- 쓰게 되는 시점에 추가한다. 스키마 소유권은 그와 무관하게 Flyway 단일이다.

CREATE TABLE user_profile (
    -- 'user:<id>' 또는 'anon:<쿠키>' (common.OwnerKey 규약).
    -- 소유자당 한 행이므로 PK 가 곧 조회 키다.
    owner_key    varchar(255) PRIMARY KEY,

    -- 본인 경력(년). 미입력이면 null — 0 과 구분해야 한다 (신입은 0, 안 적은 건 null).
    years_of_exp smallint,

    -- 보유 스택 문자열 배열. 원문 그대로 담고 정규화·별칭 해석은 읽는 쪽이 한다.
    -- (사용자가 적은 표기를 화면에 그대로 되돌려 줘야 하므로 저장 시점에 뭉개지 않는다.)
    stacks       jsonb        NOT NULL DEFAULT '[]'::jsonb,

    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    -- 음수 연차와 비현실적인 값을 막는다. 상한은 데이터 오염 방어용이지 의미상의 제한이 아니다.
    CONSTRAINT user_profile_years_range CHECK (years_of_exp IS NULL OR years_of_exp BETWEEN 0 AND 70),
    -- stacks 는 반드시 배열이어야 한다. 객체나 문자열이 들어오면 읽는 쪽이 조용히 깨진다.
    CONSTRAINT user_profile_stacks_is_array CHECK (jsonb_typeof(stacks) = 'array')
);

COMMENT ON TABLE user_profile IS
    '이력서(3단계)보다 훨씬 약한 근거다. 여기서 나온 판정에 MET/WEAK/MISSING 어휘를 쓰지 않는다 — '
    '갭 분석은 이력서 문장을 근거로 대지만 이건 스택 이름이 겹치는지만 본다. '
    '화면에서도 "내 스택 아님" 정도까지만 말한다 (스펙 §4.5 "지어내지 않는다").';
