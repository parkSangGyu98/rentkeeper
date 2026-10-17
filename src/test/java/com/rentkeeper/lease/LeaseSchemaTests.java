package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.support.TestData;

/**
 * V13·V14 스키마 제약 (ADR-018·019): 기간 겹침 EXCLUDE([) 경계, 종료 계약 제외), 복합 FK(다른 임대인 연결
 * 차단), 삭제 제한, 암호문 형식, 임대인 파기 CASCADE.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeaseSchemaTests {

	private static final String CIPHER = "'v1:QUJD'";

	@Autowired
	JdbcTemplate jdbc;

	long landlordA;

	long landlordB;

	long unitA;

	long tenantA;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
		landlordA = landlord("a@example.com");
		landlordB = landlord("b@example.com");
		long propertyA = property(landlordA, "행복빌라");
		unitA = unit(landlordA, propertyA, "101호");
		tenantA = tenant(landlordA, "a".repeat(64));
	}

	@Test
	void activeLeasesOfSameUnitCannotOverlapButMayTouchOnMoveOutDay() {
		lease(landlordA, unitA, tenantA, "2026-01-01", "2026-12-31", "ACTIVE");

		assertThatThrownBy(() -> lease(landlordA, unitA, tenantA, "2026-12-30", "2027-06-30", "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ex_lease_unit_active_period");
		assertThatCode(() -> lease(landlordA, unitA, tenantA, "2026-12-31", "2027-12-31", "ACTIVE"))
				.as("[): 나가는 날(12/31)에 다음 계약이 들어올 수 있다").doesNotThrowAnyException();
	}

	@Test
	void endedLeasesAreExcludedFromOverlapCheck() {
		jdbc.update(
				"insert into lease (landlord_id, unit_id, tenant_id, deposit, due_day, start_date, end_date, status, "
						+ "ended_on, bank_name, account_no_enc, account_holder_enc, created_at, updated_at) values (?, ?, ?, 0, 1, "
						+ "'2026-01-01', '2026-12-31', 'ENDED', '2026-05-31', '국민은행', " + CIPHER + ", " + CIPHER
						+ ", now(), now())",
				landlordA, unitA, tenantA);

		assertThatCode(() -> lease(landlordA, unitA, tenantA, "2026-06-01", "2027-05-31", "ACTIVE"))
				.doesNotThrowAnyException();
	}

	@Test
	void compositeForeignKeysRejectOtherLandlordsRows() {
		long propertyB = property(landlordB, "남의빌라");

		assertThatThrownBy(() -> unit(landlordA, propertyB, "201호")).as("A의 호실이 B의 건물을 가리킬 수 없다")
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_unit_property");
		long tenantB = tenant(landlordB, "b".repeat(64));
		assertThatThrownBy(() -> lease(landlordA, unitA, tenantB, "2026-01-01", "2026-12-31", "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_lease_tenant");
		assertThatThrownBy(() -> lease(landlordB, unitA, tenantB, "2026-01-01", "2026-12-31", "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_lease_unit");
		long leaseA = lease(landlordA, unitA, tenantA, "2026-01-01", "2026-12-31", "ACTIVE");
		assertThatThrownBy(() -> history(landlordB, leaseA, "2026-01-01"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_lease_rent_history_lease");
	}

	@Test
	void parentsWithChildrenCannotBeDeleted() {
		lease(landlordA, unitA, tenantA, "2026-01-01", "2026-12-31", "ACTIVE");

		assertThatThrownBy(() -> jdbc.update("delete from property where landlord_id = ?", landlordA))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_unit_property");
		assertThatThrownBy(() -> jdbc.update("delete from unit where id = ?", unitA))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_lease_unit");
		assertThatThrownBy(() -> jdbc.update("delete from tenant where id = ?", tenantA))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_lease_tenant");
	}

	@Test
	void deletingLeaseRemovesItsHistory() {
		long leaseA = lease(landlordA, unitA, tenantA, "2026-01-01", "2026-12-31", "ACTIVE");
		history(landlordA, leaseA, "2026-01-01");

		jdbc.update("delete from lease where id = ?", leaseA);

		assertThat(count("lease_rent_history")).isZero();
	}

	@Test
	void plaintextAndInvalidValuesAreRejected() {
		assertThatThrownBy(
				() -> jdbc.update(
						"insert into tenant (landlord_id, name_enc, phone_enc, phone_hash, created_at, "
								+ "updated_at) values (?, '홍길동', " + CIPHER + ", ?, now(), now())",
						landlordA, "c".repeat(64)))
				.as("평문 이름").hasMessageContaining("ck_tenant_encrypted");
		assertThatThrownBy(() -> tenant(landlordA, "a".repeat(64))).as("같은 임대인 안 연락처 중복")
				.hasMessageContaining("uk_tenant_landlord_phone");
		assertThatCode(() -> tenant(landlordB, "a".repeat(64))).as("다른 임대인은 같은 번호 가능").doesNotThrowAnyException();
		assertThatThrownBy(() -> lease(landlordA, unitA, tenantA, "2026-01-01", "2026-01-01", "ACTIVE"))
				.hasMessageContaining("ck_lease_period");
		long leaseA = lease(landlordA, unitA, tenantA, "2026-01-01", "2026-12-31", "ACTIVE");
		assertThatThrownBy(() -> history(landlordA, leaseA, "2026-01-15"))
				.hasMessageContaining("ck_lease_rent_history_effective_from");
		assertThatThrownBy(() -> jdbc.update("update lease set status = 'ENDED' where id = ?", leaseA))
				.as("종료일 없이 ENDED").hasMessageContaining("ck_lease_ended");
		assertThatThrownBy(() -> property(landlordA, "행복빌라")).hasMessageContaining("uk_property_landlord_name");
		assertThatThrownBy(() -> unit(landlordA, propertyOf(unitA), "101호"))
				.hasMessageContaining("uk_unit_property_name");
	}

	@Test
	void purgingLandlordDeletesWholeGraphButNotOthers() {
		long leaseA = lease(landlordA, unitA, tenantA, "2026-01-01", "2026-12-31", "ACTIVE");
		history(landlordA, leaseA, "2026-01-01");
		long propertyB = property(landlordB, "남의빌라");
		unit(landlordB, propertyB, "101호");

		// 탈퇴 파기 배치와 같은 문장 (WithdrawnAccountPurger)
		jdbc.update("delete from landlord where id = ?", landlordA);

		for (String table : new String[]{"property", "unit", "tenant", "lease", "lease_rent_history"}) {
			assertThat(count(table + " where landlord_id = " + landlordA)).as(table).isZero();
		}
		assertThat(count("unit where landlord_id = " + landlordB)).isOne();
	}

	// ---------------------------------------------------------------- 도우미

	private long landlord(String email) {
		return insert("insert into landlord (auth_provider, email, password_hash, name, created_at, updated_at) "
				+ "values ('LOCAL', ?, '{bcrypt}x', '임대인', now(), now()) returning id", email);
	}

	private long property(long landlordId, String name) {
		return insert("insert into property (landlord_id, name, created_at, updated_at) values (?, ?, now(), now()) "
				+ "returning id", landlordId, name);
	}

	private long unit(long landlordId, long propertyId, String name) {
		return insert("insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
				+ "values (?, ?, ?, 'ONE_ROOM', now(), now()) returning id", landlordId, propertyId, name);
	}

	private long tenant(long landlordId, String phoneHash) {
		return insert(
				"insert into tenant (landlord_id, name_enc, phone_enc, phone_hash, created_at, updated_at) "
						+ "values (?, " + CIPHER + ", " + CIPHER + ", ?, now(), now()) returning id",
				landlordId, phoneHash);
	}

	private long lease(long landlordId, long unitId, long tenantId, String start, String end, String status) {
		return insert("insert into lease (landlord_id, unit_id, tenant_id, deposit, due_day, start_date, end_date, "
				+ "status, bank_name, account_no_enc, account_holder_enc, created_at, updated_at) values (?, ?, ?, 0, 5, "
				+ "cast(? as date), cast(? as date), ?, '국민은행', " + CIPHER + ", " + CIPHER
				+ ", now(), now()) returning id", landlordId, unitId, tenantId, start, end, status);
	}

	private long history(long landlordId, long leaseId, String effectiveFrom) {
		return insert("insert into lease_rent_history (landlord_id, lease_id, monthly_rent, maintenance_fee, "
				+ "effective_from, created_at, updated_at) values (?, ?, 500000, 50000, cast(? as date), now(), now()) "
				+ "returning id", landlordId, leaseId, effectiveFrom);
	}

	private long propertyOf(long unitId) {
		return jdbc.queryForObject("select property_id from unit where id = ?", Long.class, unitId);
	}

	private long insert(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private int count(String fromWhere) {
		return jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class);
	}

}
