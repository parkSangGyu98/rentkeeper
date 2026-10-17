package com.rentkeeper;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 PostgreSQL 컨테이너. {@code @ServiceConnection}이 컨테이너의 접속 정보로 DataSource를
 * 구성하므로 spring.datasource.* 를 따로 설정하지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// docker/docker-compose.yml과 같은 태그를 유지한다
	static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE);
	}

}
