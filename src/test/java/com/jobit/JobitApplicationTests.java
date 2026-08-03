package com.jobit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 이 테스트가 지키는 것은 "앱이 뜬다" 하나지만, 그 과정에서 다음이 전부 검증된다.
 *
 * <ul>
 *   <li>Flyway 마이그레이션 V1~V5가 실제로 적용된다</li>
 *   <li>엔티티 매핑이 그 스키마와 맞는다</li>
 *   <li>리포지토리의 JPQL이 파싱된다 — 오타는 여기서만 잡힌다</li>
 *   <li>빈 의존성이 전부 풀린다</li>
 * </ul>
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
