package com.jobit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 컨텍스트를 띄우는 테스트용 Postgres.
 *
 * <p>운영/개발에서는 {@code spring-boot-docker-compose}가 {@code compose.yaml}을 보고 DB를
 * 붙여 주지만, 그 의존성은 {@code developmentOnly} 스코프라 <b>테스트 classpath에는 없다.</b>
 * 그래서 테스트는 스스로 DB를 마련해야 한다.
 *
 * <p>이미지는 {@code compose.yaml}과 같은 {@code pgvector/pgvector:pg17}이다. 기본 postgres
 * 이미지를 쓰면 {@code V1__enable_pgvector.sql}의 {@code CREATE EXTENSION vector}가 실패한다.
 * Testcontainers는 이미지 이름이 {@code postgres}가 아니면 거부하므로 호환 선언이 필요하다.
 *
 * <p>{@code @ServiceConnection}이 DataSource 프로퍼티를 자동으로 채운다 — 접속 정보를 손으로
 * 쓰지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17")
			.asCompatibleSubstituteFor("postgres"));
	}
}
