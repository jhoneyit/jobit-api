-- 리라이트 제안은 gap_item 당 하나다 (스펙 §4.4 — "WEAK 항목만, 해당 bullet 하나씩").
--
-- V2 는 이 제약 없이 테이블만 만들었다. 유니크가 없으면 같은 항목을 동시에 리라이트한
-- 두 요청이 제안을 두 줄 만들고, 화면은 어느 쪽을 보여줄지 알 수 없다. 갭 분석의
-- (resume_id, job_posting_id) 유니크와 같은 역할 — 캐시 키이자 경합의 최종 방어선이다.
ALTER TABLE rewrite_suggestion
    ADD CONSTRAINT rewrite_suggestion_gap_item_unique UNIQUE (gap_item_id);
