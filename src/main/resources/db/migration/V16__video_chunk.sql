-- 영상 QnA (3분할 화면의 우측 채팅) — 자막 청크 + 임베딩.
--
-- V14 는 "자막 원문은 저장하지 않는다"고 결정했는데, QnA 가 그 전제를 뒤집는다: 질문에
-- 답하려면 실제 발화가 근거로 필요하다. 통째로 저장하는 대신 **검색 단위로 쪼개 임베딩과
-- 함께** 저장한다 — 1시간 영상 자막은 num_ctx 에 다 안 들어가므로, 질문마다 관련 구간을
-- pgvector 로 찾아 그것만 프롬프트에 싣는다 (갭 분석 후보 추림과 같은 RAG 구조).
--
-- 요약 청크(8,000자)와 별개로 ~1,000자 세립 청크다 — 검색 정밀도가 목적이라 단위가 다르다.
CREATE TABLE video_chunk (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    video_summary_id uuid NOT NULL REFERENCES video_summary (id) ON DELETE CASCADE,
    start_sec        int  NOT NULL,
    content          text NOT NULL,
    embedding        vector(1024),
    sort_order       int  NOT NULL
);
CREATE INDEX idx_video_chunk_summary ON video_chunk (video_summary_id, sort_order);
