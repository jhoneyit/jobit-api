-- 주제 게이트 (영상 요약) — 면접·취업·커리어와 무관한 영상은 요약하지 않는다.
--
-- REJECTED 를 FAILED 와 구분하는 이유: 실패는 "다시 하면 될 수도 있다"이고 거부는
-- "대상이 아니다"라 화면 문구와 사용자의 다음 행동이 다르다. 목록에 "실패"로 뜨면
-- 사용자는 서버 탓을 하고, "대상 아님"으로 뜨면 다른 영상을 넣는다.
ALTER TABLE video_summary DROP CONSTRAINT video_summary_status_check;
ALTER TABLE video_summary ADD CONSTRAINT video_summary_status_check
    CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'REJECTED'));
