package com.rentkeeper.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.common.crypto.FieldEncryptor;
import com.rentkeeper.dashboard.application.DashboardService;
import com.rentkeeper.dashboard.application.DashboardService.DashboardView;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 대시보드 응답 시간 (PRD 8.3: p95 500ms). 오래 걸려 일반 빌드에서는 빠지고
 * {@code gradlew perfTest}로만 돈다. 결과는 ADR-027에 적는다.
 * <p>
 * 가장 무거운 임대인을 만든다: 한 임대인이 건물 20개 × 호실 1,000개 = 계약 2만 건, 지난 12개월 + 이번 달 청구 26만 건
 * (지난 달은 3%가 납부일 지남, 이번 달은 5%가 세입자 신고). 오늘 2026-10-17(서울).
 */
@Tag("perf")
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class DashboardThroughputTests {

	private static final int PROPERTIES = 20;

	private static final int UNITS_PER_PROPERTY = 1_000;

	private static final int RUNS = 30;

	@Autowired
	DashboardService dashboardService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	FieldEncryptor encryptor;

	@Test
	void dashboardOfTwentyThousandLeasesWithinHalfASecond() {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		long landlordId = seed();
		long oneProperty = jdbc.queryForObject("select min(id) from property", Long.class);
		jdbc.execute("analyze");

		DashboardView all = dashboardService.view(landlordId, null);
		assertThat(all.charges().thisMonth().count()).isEqualTo(PROPERTIES * UNITS_PER_PROPERTY);
		assertThat(all.charges().reported().count()).isEqualTo(PROPERTIES * UNITS_PER_PROPERTY / 20);
		assertThat(all.charges().overdueRows()).hasSize(DashboardService.LIST_LIMIT);

		Duration allP95 = p95(() -> dashboardService.view(landlordId, null));
		Duration propertyP95 = p95(() -> dashboardService.view(landlordId, oneProperty));
		System.out.printf("DASHBOARD p95 all: %d ms, one property (%d units): %d ms%n", allP95.toMillis(),
				UNITS_PER_PROPERTY, propertyP95.toMillis());
		for (String sql : List.of(
				"select count(*), sum(total_amount) from charge where landlord_id = " + landlordId
						+ " and status = 'OVERDUE'",
				"select * from charge where landlord_id = " + landlordId
						+ " and status = 'OVERDUE' order by due_date, id limit 10",
				"select count(*) from charge where landlord_id = " + landlordId
						+ " and billing_month = '2026-10-01' and status <> 'CANCELED'")) {
			System.out.println("DASHBOARD plan: " + sql + "%n".formatted()
					+ String.join("%n".formatted(), jdbc.queryForList("explain analyze " + sql, String.class)));
		}
		assertThat(allP95).isLessThan(Duration.ofMillis(500));
		assertThat(propertyP95).isLessThan(Duration.ofMillis(500));
		TestData.reset(jdbc);
	}

	private static Duration p95(Runnable call) {
		call.run(); // 준비 운동
		List<Long> nanos = new ArrayList<>();
		for (int i = 0; i < RUNS; i++) {
			long started = System.nanoTime();
			call.run();
			nanos.add(System.nanoTime() - started);
		}
		Collections.sort(nanos);
		return Duration.ofNanos(nanos.get((int) Math.ceil(RUNS * 0.95) - 1));
	}

	/** 임대인 1명, 계약 2만 건, 청구 26만 건 */
	private long seed() {
		// 목록에 보이는 행은 이름을 복호화하므로 실제 암호문을 쓴다 (용도별 AAD라 다른 행에 복사해도 풀린다)
		String accountNo = encryptor.encrypt("1234567890", "lease.account_no");
		String accountHolder = encryptor.encrypt("김임대", "lease.account_holder");
		String tenantName = encryptor.encrypt("세입자", "tenant.name");
		String tenantPhone = encryptor.encrypt("01012345678", "tenant.phone");

		long landlordId = jdbc.queryForObject("insert into landlord (auth_provider, email, password_hash, name, "
				+ "created_at, updated_at) values ('LOCAL', 'perf@example.com', '{bcrypt}x', '임대인', now(), now()) "
				+ "returning id", Long.class);
		jdbc.update("insert into property (landlord_id, name, created_at, updated_at) "
				+ "select ?, '빌라' || g, now(), now() from generate_series(1, ?) g", landlordId, PROPERTIES);
		jdbc.update("insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
				+ "select p.landlord_id, p.id, g || '호', 'ONE_ROOM', now(), now() "
				+ "from property p cross join generate_series(1, ?) g", UNITS_PER_PROPERTY);
		jdbc.update(
				"insert into tenant (landlord_id, name_enc, phone_enc, phone_hash, created_at, updated_at) "
						+ "select landlord_id, ?, ?, md5(id::text) || md5(id::text), now(), now() from unit",
				tenantName, tenantPhone);
		// 1%는 90일 안에 끝난다
		jdbc.update(
				"insert into lease (landlord_id, unit_id, tenant_id, deposit, due_day, start_date, end_date, status, "
						+ "bank_name, account_no_enc, account_holder_enc, created_at, updated_at) "
						+ "select u.landlord_id, u.id, t.id, 0, 5, '2025-01-01', "
						+ "case when u.id % 100 = 0 then date '2026-12-01' else date '2028-01-01' end, 'ACTIVE', "
						+ "'국민은행', ?, ?, now(), now() from unit u join tenant t "
						+ "on t.landlord_id = u.landlord_id and t.phone_hash = md5(u.id::text) || md5(u.id::text)",
				accountNo, accountHolder);
		// 지난 12개월: 3% 납부일 지남, 나머지 다 받음
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, paid_amount, "
				+ "status, source, issued_at, paid_at, created_at, updated_at) "
				+ "select l.landlord_id, l.id, m::date, m::date + 4, 550000, case when l.id % 33 = 0 then 0 else 550000 end, "
				+ "case when l.id % 33 = 0 then 'OVERDUE' else 'PAID' end, 'AUTO', now(), "
				+ "case when l.id % 33 = 0 then null else now() end, now(), now() from lease l "
				+ "cross join generate_series(date '2025-10-01', date '2026-09-01', interval '1 month') g(m)");
		// 이번 달: 5% 세입자 신고, 60% 다 받음, 나머지 받을 차례
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, paid_amount, "
				+ "status, source, issued_at, paid_at, tenant_reported_at, created_at, updated_at) "
				+ "select l.landlord_id, l.id, date '2026-10-01', date '2026-10-25', 550000, "
				+ "case when l.id % 20 = 0 then 0 when l.id % 10 < 6 then 550000 else 0 end, "
				+ "case when l.id % 20 = 0 then 'TENANT_REPORTED' when l.id % 10 < 6 then 'PAID' else 'ISSUED' end, "
				+ "'AUTO', now(), case when l.id % 20 <> 0 and l.id % 10 < 6 then now() end, "
				+ "case when l.id % 20 = 0 then now() end, now(), now() from lease l");
		return landlordId;
	}

}
