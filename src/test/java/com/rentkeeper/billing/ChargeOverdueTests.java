package com.rentkeeper.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.billing.application.ChargeGenerator;
import com.rentkeeper.billing.application.ChargeOverdueMarker;
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

/** 납부일 지남 배치 (ADR-023, M3 결정 7): 대상 상태, 신고 제외, 서울 자정 경계, 다시 돌려도 같음 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class ChargeOverdueTests {

	@Autowired
	ChargeGenerator generator;

	@Autowired
	ChargeOverdueMarker marker;

	@Autowired
	LeaseService leaseService;

	@Autowired
	PropertyService propertyService;

	@Autowired
	LandlordService landlordService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	long landlordId;

	long propertyId;

	int seq;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
		clock.set(seoul("2026-10-01T09:00"));
		landlordId = landlordService.registerLocal(EmailAddress.of("overdue@example.com"), "{bcrypt}x", "임대인");
		propertyId = propertyService.create(landlordId, new PropertyInput("행복빌라", null, null));
	}

	@Test
	void marksIssuedAndPartiallyPaidButNotReportedOrPaid() {
		long issued = chargeOf(register());
		long partial = chargeOf(register());
		long reported = chargeOf(register());
		long paid = chargeOf(register());
		jdbc.update("update charge set paid_amount = 100000, status = 'PARTIALLY_PAID' where id = ?", partial);
		jdbc.update("update charge set tenant_reported_at = now(), status = 'TENANT_REPORTED' where id = ?", reported);
		jdbc.update("update charge set paid_amount = total_amount, status = 'PAID', paid_at = now() where id = ?",
				paid);

		clock.set(seoul("2026-10-06T00:10")); // 납부일 10/5 다음 날
		ChargeOverdueMarker.Result result = marker.run();

		assertThat(result.marked()).isEqualTo(2);
		assertThat(status(issued)).isEqualTo("OVERDUE");
		assertThat(status(partial)).as("일부 받음도 납부일 지남").isEqualTo("OVERDUE");
		assertThat(status(reported)).as("세입자 신고는 건드리지 않는다 (M3 결정 7)").isEqualTo("TENANT_REPORTED");
		assertThat(status(paid)).isEqualTo("PAID");
		assertThat(
				jdbc.queryForList("select from_status || '>' || to_status || ':' || reason from charge_status_history "
						+ "where charge_id = ? and to_status = 'OVERDUE'", String.class, partial))
				.containsExactly("PARTIALLY_PAID>OVERDUE:납부일 지남");
		assertThat(marker.run().marked()).as("다시 돌려도 같다").isZero();
	}

	@Test
	void seoulMidnightAfterDueDateDecides() {
		long charge = chargeOf(register());

		clock.set(Instant.parse("2026-10-05T14:59:59Z")); // 서울 10/5 23:59:59 = 납부일 당일
		assertThat(marker.run().marked()).isZero();
		assertThat(status(charge)).isEqualTo("ISSUED");

		clock.set(Instant.parse("2026-10-05T15:00:00Z")); // 서울 10/6 00:00
		assertThat(marker.run().marked()).isEqualTo(1);
		assertThat(status(charge)).isEqualTo("OVERDUE");
	}

	@Test
	void runsAtTenPastMidnightSeoul() {
		CronExpression cron = CronExpression.parse("0 10 0 * * *");
		ZonedDateTime before = Instant.parse("2026-10-05T15:00:00Z").atZone(ClockConfig.SEOUL);
		assertThat(cron.next(before).toInstant()).as("서울 10/6 00:10 = UTC 10/5 15:10")
				.isEqualTo(Instant.parse("2026-10-05T15:10:00Z"));
	}

	private long register() {
		long unit = propertyService.addUnit(landlordId, propertyId,
				new UnitInput((++seq) + "01호", UnitType.ONE_ROOM, null));
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.parse("2026-10-01"), LocalDate.parse("2027-10-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(landlordId, new LeaseRegistration(unit, new PersonName("세입자" + seq),
				PhoneNumber.parse("0100000%04d".formatted(seq)), null, 500_000, 0, terms, false));
	}

	/** 10월분 청구를 만든다 (10/1 등록, 납부일 10/5) */
	private long chargeOf(long lease) {
		Instant before = clock.instant();
		clock.set(seoul("2026-10-02T03:00"));
		generator.run();
		clock.set(before);
		return jdbc.queryForObject("select id from charge where lease_id = ?", Long.class, lease);
	}

	private String status(long chargeId) {
		return jdbc.queryForObject("select status from charge where id = ?", String.class, chargeId);
	}

	private static Instant seoul(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(ClockConfig.SEOUL).toInstant();
	}

}
