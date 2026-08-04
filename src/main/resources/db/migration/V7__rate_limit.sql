-- 레이트 리밋 (스펙 §6 "세션당 호출 횟수 제한 + 결과 캐싱").
--
-- 이관 전에는 프론트에만 있었다. 이제 프론트가 이 서버를 호출하는 구조라 여기가 진짜 문이고,
-- 여기에 잠금이 없으면 8080 을 직접 때리는 것만으로 크레딧이 녹는다.
--
-- **메모리가 아니라 DB 에 둔다.** 프론트 구현은 인스턴스 메모리라 재시작하면 카운터가 0으로
-- 돌아가고 인스턴스를 늘리면 각자 따로 셌다. 어차피 Postgres 가 있으므로 여기서 해결한다.

CREATE TABLE rate_limit_bucket (
    -- 'user:<id>' 또는 'anon:<쿠키>' (common.OwnerKey 규약)
    owner_key    text        NOT NULL,
    -- 고정 창(fixed window). date_trunc('hour', now()) 값이 그대로 들어간다.
    window_start timestamptz NOT NULL,
    count        int         NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_key, window_start)
);

-- 만료된 창을 지우는 정리 작업용. 조회는 PK 로 하므로 이 인덱스는 삭제 전용이다.
CREATE INDEX idx_rate_limit_window ON rate_limit_bucket (window_start);

COMMENT ON TABLE rate_limit_bucket IS
    '고정 창 방식이라 창 경계에서 순간적으로 한도의 두 배가 통과할 수 있다 '
    '(예: 12:59에 20회 + 13:00에 20회). 비용 방어가 목적이라 이 정도 오차는 허용한다 — '
    '총액은 llm.daily-budget-usd 가 따로 막는다.';
