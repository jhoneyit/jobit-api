package com.jobit.video;

/**
 * pgvector 리터럴 변환 — {@code ResumeBulletEmbeddingRepository#toVectorLiteral} 과 같은
 * 구현이다. 세 패키지가 같은 변환을 쓰는데 각자의 리포지토리에 package-private 로 갇혀 있어
 * 여기서 한 번 더 산다. 넷째 사용처가 생기면 llm 패키지로 올릴 것.
 */
final class RequirementEmbeddingRepositoryBridge {

	private RequirementEmbeddingRepositoryBridge() {
	}

	static String toVectorLiteral(float[] embedding) {
		if (embedding == null || embedding.length == 0) {
			throw new IllegalArgumentException("embedding must not be empty");
		}
		StringBuilder sb = new StringBuilder(embedding.length * 12 + 2);
		sb.append('[');
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(embedding[i]);
		}
		return sb.append(']').toString();
	}
}
