-- 임베딩 차원을 1536 → 1024 로 바꾼다 (제공자 전환: OpenAI text-embedding-3-small → Ollama).
--
-- **1024 는 취향이 아니라 제약이다.** 1536차원을 내는 로컬 임베딩 모델이 사실상 없다
-- (qwen3-embedding:0.6b·bge-m3·mxbai 전부 1024, nomic 은 768). 모델을 스키마에 맞추는 대신
-- 스키마를 옮겼다.
--
-- **alter type 이 아니라 drop + add 다.** 차원이 다른 두 임베딩 공간 사이에는 대응이 없어서
-- 기존 벡터를 1024차원으로 "줄일" 방법이 없다. 값을 살릴 수 없으니 컬럼을 새로 만든다.
--
-- **행은 지우지 않는다.** resume_bullet 의 문장 자체는 그대로 쓸모가 있고,
-- ResumeBulletEmbeddingRepository 의 조회가 이미 `embedding is not null` 로 거른다 —
-- 벡터가 빈 문장은 갭 분석 후보에 조용히 끼어들지 않고 그냥 빠진다.
-- 다만 그 이력서들은 **다시 업로드해야 갭 분석에 쓰인다.** countWithEmbedding 이 그 상태를 센다.

alter table resume_bullet drop column embedding;
alter table resume_bullet add column embedding vector(1024);
