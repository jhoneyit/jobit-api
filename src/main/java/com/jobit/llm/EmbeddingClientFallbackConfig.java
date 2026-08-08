package com.jobit.llm;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 임베딩 키가 없을 때 {@link EmbeddingClient} 자리를 채운다.
 *
 * <p>{@code JdParserFallbackConfig} 와 같은 구조다 — 이게 없으면 이력서 서비스를 조립하지 못해
 * 앱이 아예 뜨지 않고, 임베딩과 무관한 기능(JD 파싱·질문 생성·면접 연습)까지 함께 죽는다.
 *
 * <p><b>호출되면 예외를 던진다. 빈 벡터를 반환하지 않는다.</b> 0 벡터를 저장하면 코사인 유사도가
 * 전부 0으로 나와 "후보를 못 찾은 것"과 "임베딩이 없는 것"이 구분되지 않는다. 그 상태로 갭 분석이
 * 돌면 근거 없는 판정이 나오는데, 그건 이 제품이 가장 하지 말아야 할 일이다 (작업 원칙).
 */
@Configuration
@Slf4j
public class EmbeddingClientFallbackConfig {

	/**
	 * 경고를 빈 메서드 안에서 낸다 — {@code JdParserFallbackConfig} 와 같은 이유다.
	 * 설정 클래스의 {@code @PostConstruct} 에 두면 폴백이 실제로 물렸는지와 무관하게 늘 찍히고,
	 * 늘 뜨는 경고는 곧 읽히지 않는다.
	 */
	@Bean
	@ConditionalOnMissingBean(EmbeddingClient.class)
	public EmbeddingClient unavailableEmbeddingClient() {
		log.warn("EmbeddingClient 구현이 없습니다 (openai.api-key 미설정). 이력서 업로드를 호출하면 실패합니다.");
		return new EmbeddingClient() {

			@Override
			public List<float[]> embedAll(List<String> texts) {
				throw new EmbeddingNotConfiguredException();
			}

			@Override
			public int dimensions() {
				throw new EmbeddingNotConfiguredException();
			}
		};
	}

	public static class EmbeddingNotConfiguredException extends IllegalStateException {

		public EmbeddingNotConfiguredException() {
			super("EmbeddingClient 구현이 등록되지 않았습니다. openai.api-key 를 넣어야 이력서 분석이 동작합니다.");
		}
	}
}
