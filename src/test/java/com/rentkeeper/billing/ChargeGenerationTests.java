package com.rentkeeper.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.billing.application.ChargeGenerator;
import com.rentkeeper.config.ClockConfig;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.application.PropertyViews.PropertyInput;
import com.rentkeeper.property.application.PropertyViews.UnitInput;
import com.rentkeeper.property.domain.UnitType;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 청구 생성 배치 (ADR-024, PRD 12장 필수 1·3): 발행과 이력, 멱등(두 번·동시), 누락 복구, 실패 격리, 서울
 * 자정·03:00 경계, 계약 수정과의 경합.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class ChargeGenerationTests {

	@Autowired
	ChargeGenerator generator;

	@Autowired
	LeaseService leaseService;

	@Autowired
	PropertyService propertyService;

	@Autowired
	LandlordService landlordService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	MutableClock clock;

	long landlordId;

	long propertyId;

	int phoneSeq;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
		clock.set(seoul("2026-10-01T09:00"));
		landlordId = landlordService.registerLocal(EmailAddress.of("billing@example.com"), "{bcrypt}x", "임대인");
		propertyId = propertyService.create(landlordId, new PropertyInput("행복빌라", null, null));
	}

	@Test
	void createsAndIssuesChargeWithItemsAndHistory() {
		long lease = register(10, "2026-10-01", "2027-10-01", 500_000, 50_000);
		clock.set(seoul("2026-10-03T03:00")); // 10월 납부일 10일 − 7일 = 3일

		assertThat(generator.run().created()).isEqualTo(1);

		assertThat(jdbc.queryForMap("select billing_month, due_date, total_amount, paid_amount, status, source, "
				+ "issued_at is not null as issued from charge where lease_id = ?", lease))
				.containsEntry("billing_month", java.sql.Date.valueOf("2026-10-01"))
				.containsEntry("due_date", java.sql.Date.valueOf("2026-10-10")).containsEntry("total_amount", 550_000L)
				.containsEntry("paid_amount", 0L).containsEntry("status", "ISSUED").containsEntry("source", "AUTO")
				.containsEntry("issued", true);
		assertThat(jdbc.queryForList("select type, label, amount from charge_item order by id"))
				.extracting(r -> r.get("type"), r -> r.get("label"), r -> r.get("amount"))
				.containsExactly(tuple("RENT", "월세", 500_000L), tuple("MAINTENANCE", "관리비", 50_000L));
		assertThat(jdbc.queryForList(
				"select from_status, to_status, actor_type, reason from charge_status_history order by id"))
				.extracting(r -> r.get("from_status"), r -> r.get("to_status"), r -> r.get("actor_type"),
						r -> r.get("reason"))
				.containsExactly(tuple(null, "SCHEDULED", "SYSTEM", "자동 생성"),
						tuple("SCHEDULED", "ISSUED", "SYSTEM", "자동 발행"));
		assertThat(jdbc.queryForList("select action, after_amount from charge_item_history order by id"))
				.extracting(r -> r.get("action"), r -> r.get("after_amount"))
				.containsExactly(tuple("ADD", 500_000L), tuple("ADD", 50_000L));
	}

	@Test
	void runningTwiceOrConcurrentlyCreatesOneChargePerMonth() throws Exception {
		register(25, "2026-10-01", "2027-10-01", 500_000, 0);
		register(28, "2026-10-01", "2027-10-01", 400_000, 0);
		clock.set(seoul("2026-10-21T03:00")); // 두 계약 모두 10월분을 만들 날, 납부일 전

		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<ChargeGenerator.Result>> results = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return generator.run();
				}));
			}
			start.countDown();
			int created = 0;
			for (Future<ChargeGenerator.Result> result : results) {
				ChargeGenerator.Result r = result.get(60, TimeUnit.SECONDS);
				assertThat(r.failed()).isZero();
				created += r.created();
			}
			assertThat(created).as("동시에 4번 돌려도 계약 2건 × 10월 1건").isEqualTo(2);
		} finally {
			executor.shutdownNow();
		}
		assertThat(generator.run().created()).as("다시 돌려도 0건").isZero();
		assertThat(count("charge")).isEqualTo(2);
		assertThat(count("charge_status_history")).isEqualTo(4);
	}

	@Test
	void recoversMissedMonthsAndMarksPastDueOnesOverdue() {
		long lease = register(5, "2026-10-01", "2027-10-01", 500_000, 0);
		// 10/1 등록, 10월 납부일 5일. 배치가 3달 동안 돌지 못하다가 1/2에 돈다
		clock.set(seoul("2027-01-02T03:00"));

		assertThat(generator.run().created()).isEqualTo(4);

		assertThat(jdbc.queryForList(
				"select billing_month, status from charge where lease_id = ? order by billing_month", lease))
				.extracting(r -> r.get("billing_month").toString(), r -> r.get("status"))
				.containsExactly(tuple("2026-10-01", "OVERDUE"), tuple("2026-11-01", "OVERDUE"),
						tuple("2026-12-01", "OVERDUE"), tuple("2027-01-01", "ISSUED"));
		assertThat(jdbc.queryForList("select reason from charge_status_history h join charge c on c.id = h.charge_id "
				+ "where c.billing_month = '2026-10-01' order by h.id", String.class))
				.containsExactly("자동 생성", "자동 발행", "납부일 지남");
	}

	@Test
	void rentChangeAffectsOnlyMonthsFromItsEffectiveMonth() {
		long lease = register(5, "2026-10-01", "2027-10-01", 500_000, 0);
		clock.set(seoul("2026-10-02T03:00"));
		generator.run();
		jdbc.update("insert into lease_rent_history (landlord_id, lease_id, monthly_rent, maintenance_fee, "
				+ "effective_from, created_at, updated_at) values (?, ?, 550000, 0, '2026-11-01', now(), now())",
				landlordId, lease);

		clock.set(seoul("2026-11-02T03:00"));
		generator.run();

		assertThat(jdbc.queryForList("select billing_month, total_amount from charge order by billing_month"))
				.extracting(r -> r.get("billing_month").toString(), r -> r.get("total_amount"))
				.as("과거 청구 금액은 그대로 (PRD 12장 필수 3)")
				.containsExactly(tuple("2026-10-01", 500_000L), tuple("2026-11-01", 550_000L));
	}

	@Test
	void oneFailingLeaseDoesNotStopOthersAndAlertsWithoutPersonalInfo(CapturedOutput output) {
		long failing = register(10, "2026-10-01", "2027-10-01", 500_000, 0);
		long ok = register(10, "2026-10-01", "2027-10-01", 400_000, 0);
		// 한 계약의 청구 저장만 DB에서 실패시킨다
		jdbc.execute("""
				create or replace function test_fail_charge() returns trigger language plpgsql as $$
				begin raise exception 'test failure'; end $$""");
		jdbc.execute("create trigger test_fail_charge before insert on charge for each row when (new.lease_id = "
				+ failing + ") execute function test_fail_charge()");
		clock.set(seoul("2026-10-03T03:00"));

		ChargeGenerator.Result result;
		try {
			result = generator.run();
		} finally {
			jdbc.execute("drop trigger test_fail_charge on charge");
			jdbc.execute("drop function test_fail_charge()");
		}

		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.created()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from charge where lease_id = ?", Integer.class, ok))
				.isEqualTo(1);
		assertThat(output).contains("CHARGE_BATCH_ALERT job=generation failed=1 created=1 leases=2")
				.doesNotContain("세입자").doesNotContain("01000000");
	}

	@Test
	void seoulMidnightDecidesTheDay() {
		register(8, "2026-10-01", "2027-10-01", 500_000, 0);
		// 11월 납부일 8일 − 7일 = 11/1. 10월분(납부일 10/8)은 등록 때 이미 만들 수 있다
		clock.set(Instant.parse("2026-10-31T14:59:59Z")); // 서울 10/31 23:59:59
		assertThat(generator.run().created()).as("10월분만").isEqualTo(1);

		clock.set(Instant.parse("2026-10-31T15:00:00Z")); // 서울 11/1 00:00:00
		assertThat(generator.run().created()).as("서울 자정이 지나면 11월분").isEqualTo(1);
		assertThat(jdbc.queryForList("select billing_month from charge order by billing_month", LocalDate.class))
				.containsExactly(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01"));
	}

	@Test
	void threeAmSeoulIsSixPmUtcPreviousDay() {
		CronExpression cron = CronExpression.parse("0 0 3 * * *");
		ZonedDateTime before = Instant.parse("2026-10-31T17:59:59Z").atZone(ClockConfig.SEOUL);
		assertThat(cron.next(before).toInstant()).as("서울 11/1 03:00 = UTC 10/31 18:00")
				.isEqualTo(Instant.parse("2026-10-31T18:00:00Z"));

		register(8, "2026-10-01", "2027-10-01", 500_000, 0);
		clock.set(Instant.parse("2026-10-31T17:59:59Z")); // 서울 11/1 02:59:59
		assertThat(generator.run().created()).as("이미 서울 11/1: 10월·11월분").isEqualTo(2);
		clock.set(Instant.parse("2026-10-31T18:00:00Z")); // 서울 11/1 03:00
		assertThat(generator.run().created()).isZero();
	}

	@Test
	void endedLeaseIsBilledUntilDayBeforeEndedOn() {
		long lease = register(5, "2026-10-01", "2027-10-01", 500_000, 0);
		jdbc.update("update lease set status = 'ENDED', ended_on = '2026-11-01' where id = ?", lease);
		clock.set(seoul("2027-01-02T03:00"));

		generator.run();

		assertThat(jdbc.queryForList("select billing_month from charge", LocalDate.class)).as("나간 날 11/1 → 10월까지")
				.containsExactly(LocalDate.parse("2026-10-01"));
	}

	@Test
	void batchWaitsForConcurrentLeaseChangeAndUsesNewTerms() throws Exception {
		long lease = register(5, "2026-10-01", "2027-10-01", 500_000, 0);
		clock.set(seoul("2026-10-30T03:00"));
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// 계약 수정이 계약 행을 잠근 채 나가는 날을 11/1로 당긴다 (아직 커밋 전)
			Future<?> change = executor.submit(() -> transaction.executeWithoutResult(status -> {
				jdbc.update("update lease set end_date = '2026-11-01' where id = ?", lease);
				locked.countDown();
				await(release);
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			Future<ChargeGenerator.Result> batch = executor.submit(generator::run);
			Thread.sleep(500);
			assertThat(batch.isDone()).as("배치는 계약 잠금을 기다린다").isFalse();

			release.countDown();
			change.get(10, TimeUnit.SECONDS);
			assertThat(batch.get(30, TimeUnit.SECONDS).failed()).isZero();
		} finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForList("select billing_month from charge", LocalDate.class))
				.as("바뀐 나가는 날(11/1) 기준: 11월분은 만들지 않는다").containsExactly(LocalDate.parse("2026-10-01"));
	}

	private long register(int dueDay, String start, String end, long rent, long fee) {
		long unit = propertyService.addUnit(landlordId, propertyId,
				new UnitInput((++phoneSeq) + "01호", UnitType.ONE_ROOM, null));
		LeaseTerms terms = new LeaseTerms(0, dueDay, LocalDate.parse(start), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(landlordId, new LeaseRegistration(unit, new PersonName("세입자" + phoneSeq),
				PhoneNumber.parse("0100000%04d".formatted(phoneSeq)), null, rent, fee, terms, false));
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private static Instant seoul(String localDateTime) {
		return java.time.LocalDateTime.parse(localDateTime).atZone(ClockConfig.SEOUL).toInstant();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
