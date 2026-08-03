-- 일반 회원가입 (이메일 + 비밀번호). 스펙 §3.6.

ALTER TABLE member
    ADD COLUMN password_hash text;

ALTER TABLE member
    DROP CONSTRAINT member_provider_check;

ALTER TABLE member
    ADD CONSTRAINT member_provider_check CHECK (provider IN ('LOCAL', 'GITHUB'));

-- LOCAL 계정은 비밀번호 해시가 반드시 있어야 한다.
-- 애플리케이션 버그로 해시 없는 계정이 생기면 로그인 불가 상태로 방치되므로 DB에서 막는다.
ALTER TABLE member
    ADD CONSTRAINT member_local_requires_password
        CHECK (provider <> 'LOCAL' OR password_hash IS NOT NULL);

-- LOCAL 계정은 provider_uid에 정규화한 이메일이 들어가므로
-- 기존 unique (provider, provider_uid)가 이메일 중복까지 막는다. 추가 제약은 없다.
