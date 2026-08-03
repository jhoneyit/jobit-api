-- 비밀번호 재설정 토큰. 스펙 §3.7.
-- 토큰 원문은 저장하지 않는다. DB가 유출되면 저장된 토큰으로 계정을 탈취할 수 있기 때문이다.

CREATE TABLE password_reset_token (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id  uuid        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 재발급 시 이전 토큰을 무효화하고, 60초 내 재요청을 걸러내기 위해 회원별 최근순 조회를 쓴다.
CREATE INDEX idx_password_reset_member ON password_reset_token (member_id, created_at DESC);

-- 만료 토큰 정리 배치용.
CREATE INDEX idx_password_reset_expires ON password_reset_token (expires_at);
