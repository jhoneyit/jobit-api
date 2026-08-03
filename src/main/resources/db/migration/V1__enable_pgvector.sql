-- 스펙 §2: pgvector 확장은 처음부터 활성화한다.
-- resume_bullet.embedding vector(1536) 컬럼이 이 확장에 의존한다.
CREATE EXTENSION IF NOT EXISTS vector;
