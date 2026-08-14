-- 질문 은행 (스펙 §5 5단계) — 요구사항 임베딩.
--
-- 질문은 이미 requirement 에 매여 쌓이고 있다 (question.requirement_id). 은행에서 꺼내 쓰려면
-- "이 요구사항과 비슷한 **다른 공고의** 요구사항"을 전역으로 찾을 수 있어야 하고, 그 축이
-- 이 컬럼이다. 모델은 이력서 임베딩과 같다 (qwen3-embedding:0.6b, 1024차원) — 요구사항과
-- 이력서 문장이 같은 공간에 있어야 갭 분석 후보 추림도 성립하므로, 여기만 다른 모델을 쓸 수 없다.
--
-- **nullable 이다.** 임베딩은 파싱 결과의 enrichment 라 실패해도 파싱을 죽이지 않고 null 로
-- 남긴다. 이 마이그레이션 이전에 파싱된 요구사항도 null 이다 — 백필하지 않고 은행이 앞으로
-- 자라게 둔다 (재파싱되면 채워진다). 검색이 `embedding is not null` 로 거른다.
ALTER TABLE requirement
    ADD COLUMN embedding vector(1024);

-- resume_bullet 과 달리 **인덱스를 만든다** (그쪽 주석이 예고한 그 자리다). 이력서 검색은
-- (resume_id) 로 좁힌 수십 행 전수 스캔이지만, 여기는 전역 검색이라 요구사항이 쌓일수록
-- 전수 스캔이 선형으로 느려진다. HNSW 는 근사지만, 참고 질문은 "가장 가까운 것"이 아니라
-- "충분히 가까운 것"이면 되므로 근사 오차가 결과 품질에 닿지 않는다.
CREATE INDEX idx_requirement_embedding ON requirement
    USING hnsw (embedding vector_cosine_ops);
