package com.jobit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 이 테스트가 지키는 것은 "앱이 뜬다" 하나지만, 그 과정에서 다음이 전부 검증된다.
 *
 * <ul>
 *   <li>Flyway 마이그레이션이 실제 Postgres에 전부 적용된다</li>
 *   <li>엔티티 매핑이 그 스키마와 맞는다 — {@code ddl-auto=validate}가 컬럼 하나까지 본다</li>
 *   <li>리포지토리의 JPQL이 파싱된다 — 오타는 여기서만 잡힌다</li>
 *   <li>빈 의존성이 전부 풀린다</li>
 * </ul>
 *
 * <p><b>매핑 검증은 공짜가 아니다.</b> {@code spring.jpa.hibernate.ddl-auto=validate}가
 * 있어야 성립한다 — 없으면 기본값이 {@code none}이라(Testcontainers Postgres는 임베디드가
 * 아니다) 컬럼명을 틀려도 컨텍스트는 그대로 뜨고 첫 조회에서야 터진다. 그 줄을 지우면
 * 이 목록의 두 번째 항목이 조용히 거짓이 된다.
 *
 * <p><b>여기서 검증되지 <i>않는</i> 것</b>: 실제 저장·조회. 매핑이 맞아도 유니크 제약이나
 * jsonb 왕복이 의도대로 도는지는 별개다 — 새 테이블은 그쪽도 한 번 태워 본다
 * ({@code InterviewPersistenceTest} 참고).
 *
 * <p>Docker가 필요하다.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class JobitApplicationTests {

	@Test
	void contextLoads() {
	}

}
