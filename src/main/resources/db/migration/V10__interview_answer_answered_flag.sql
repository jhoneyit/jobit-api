-- "답했는가"를 발화 원문과 분리한다 (docs/interview-practice-design.md §7).
--
-- **왜 필요한가.** transcript 는 개인정보라 TTL 이 지나면 지운다. 그런데 지금까지 "답했는가"를
-- `transcript IS NOT NULL` 로 판단했으므로, 원문을 지우는 순간 그 기록은 "시간 안에 답하지
-- 못했다"로 바뀐다. 80점을 받은 답변이 90일 뒤에 무응답으로 보이는 것이다.
--
-- 개인정보 삭제의 취지는 **내용을 지우는 것이지 사실을 지우는 것이 아니다.** 점수·피드백을
-- 남기기로 한 것과 같은 이유로 "답했다"도 남아야 한다. 그래서 파생값이 아니라 컬럼으로 둔다.
--
-- docs/api.md 가 이미 이 구분을 계약으로 적어 두었다:
--   answered=false, transcript=null  → 시간 내에 답하지 못했다
--   answered=true,  transcript=null  → TTL 이 지나 원문만 지웠다

ALTER TABLE interview_answer
    ADD COLUMN answered boolean NOT NULL DEFAULT false;

-- 기존 행 보정. 아직 만료된 원문이 없으므로 transcript 유무가 곧 답변 여부다.
UPDATE interview_answer SET answered = (transcript IS NOT NULL);

COMMENT ON COLUMN interview_answer.answered IS
    'transcript 가 지워져도 남는다. 원문 없이 answered=true 인 행은 "보관 기간이 지나 지워짐"이고, '
    'answered=false 는 "제한 시간 안에 답하지 못함"이다 — 화면이 이 둘을 다르게 보여준다.';
