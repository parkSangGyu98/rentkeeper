package com.rentkeeper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.config.ClockConfig;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RentKeeperApplicationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Clock clock;

	@Test
	void flywayAppliesV1AndEnablesBtreeGist() {
		Integer extensionCount = jdbcTemplate
				.queryForObject("select count(*) from pg_extension where extname = 'btree_gist'", Integer.class);
		Boolean v1Succeeded = jdbcTemplate
				.queryForObject("select success from flyway_schema_history where version = '1'", Boolean.class);

		assertThat(extensionCount).isEqualTo(1);
		assertThat(v1Succeeded).isTrue();
	}

	@Test
	void clockUsesSeoulTimeZone() {
		assertThat(clock.getZone()).isEqualTo(ClockConfig.SEOUL);
	}

}
