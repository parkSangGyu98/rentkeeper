package com.rentkeeper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 모든 테이블과 컬럼에 한글 설명(COMMENT)이 있는지 확인한다 (CLAUDE.md "DB 설명(COMMENT) 규칙"). 새
 * 마이그레이션에서 설명을 빠뜨리면 빌드가 실패한다. Flyway가 관리하는 flyway_schema_history는 제외한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaCommentsTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void everyTableHasComment() {
		assertThat(jdbcTemplate.queryForList("""
				select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
				  and coalesce(obj_description(c.oid, 'pg_class'), '') = ''
				order by 1
				""", String.class)).as("설명(COMMENT ON TABLE)이 없는 테이블").isEmpty();
	}

	@Test
	void everyColumnHasComment() {
		assertThat(jdbcTemplate.queryForList("""
				select c.relname || '.' || a.attname from pg_attribute a
				join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
				  and a.attnum > 0 and not a.attisdropped
				  and coalesce(col_description(c.oid, a.attnum), '') = ''
				order by 1
				""", String.class)).as("설명(COMMENT ON COLUMN)이 없는 컬럼").isEmpty();
	}

}
