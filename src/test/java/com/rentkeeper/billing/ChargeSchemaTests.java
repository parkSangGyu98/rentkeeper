package com.rentkeeper.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeItem;
import com.rentkeeper.billing.domain.ChargeItemHistory;
import com.rentkeeper.billing.domain.ChargeItemHistoryRepository;
import com.rentkeeper.billing.domain.ChargeItemRepository;
import com.rentkeeper.billing.domain.ChargeItemType;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeSource;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistory;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.support.TestData;

/**
 * V16 스키마 제약 (ADR-023): 같은 달 하나(취소 포함), 복합 FK, 상태와 시각 컬럼의 일치, 청구가 있는 계약 삭제 금지,
 * 임대인 파기 CASCADE, 엔티티 매핑과 낙관적 락 번호.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChargeSchemaTests {

	private static final String CIPHER = "'v1:QUJD'";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	ChargeRepository charges;

	@Autowired
	ChargeItemRepository items;

	@Autowired
	ChargeStatusHistoryRepository statusHistories;

	@Autowired
	ChargeItemHistoryRepository itemHistories;

	long landlordA;

	long landlordB;

	long leaseA;

	long leaseB;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
		landlordA = landlord("a@example.com");
		landlordB = landlord("b@example.com");
		leaseA = lease(landlordA, "a");
		leaseB = lease(landlordB, "b");
	}

	@Test
	void oneChargePerLeaseAndMonthEvenAfterCancel() {
		long first = charge(landlordA, leaseA, "2026-11-01", "ISSUED");
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못 만듦' where id = ?",
				first);

		assertThatThrownBy(() -> charge(landlordA, leaseA, "2026-11-01", "ISSUED")).as("취소한 달도 다시 만들 수 없다")
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_charge_lease_month");
		assertThatCode(() -> charge(landlordA, leaseA, "2026-12-01", "ISSUED")).doesNotThrowAnyException();
	}

	@Test
	void compositeForeignKeysRejectOtherLandlordsRows() {
		assertThatThrownBy(() -> charge(landlordA, leaseB, "2026-11-01", "ISSUED")).as("A의 청구가 B의 계약을 가리킬 수 없다")
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_charge_lease");

		long chargeB = charge(landlordB, leaseB, "2026-11-01", "ISSUED");
		assertThatThrownBy(
				() -> jdbc.update(
						"insert into charge_item (landlord_id, charge_id, type, label, amount, "
								+ "created_at, updated_at) values (?, ?, 'RENT', '월세', 1, now(), now())",
						landlordA, chargeB))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_charge_item_charge");
	}

	@Test
	void statusMustMatchTimestampColumns() {
		assertThatThrownBy(() -> insertCharge("ISSUED", null, null, null, null)).as("받을 차례인데 발행 시각 없음")
				.hasMessageContaining("ck_charge_issued_at");
		assertThatThrownBy(() -> insertCharge("SCHEDULED", "now()", null, null, null)).as("준비 중인데 발행 시각 있음")
				.hasMessageContaining("ck_charge_issued_at");
		assertThatThrownBy(() -> insertCharge("PAID", "now()", null, null, null)).as("다 받음인데 다 받은 시각 없음")
				.hasMessageContaining("ck_charge_paid_at");
		assertThatThrownBy(() -> insertCharge("ISSUED", "now()", "now()", null, null)).as("다 받음이 아닌데 다 받은 시각 있음")
				.hasMessageContaining("ck_charge_paid_at");
		assertThatThrownBy(() -> insertCharge("CANCELED", null, null, "now()", null)).as("취소 사유 없음")
				.hasMessageContaining("ck_charge_canceled");
		assertThatThrownBy(() -> insertCharge("ISSUED", "now()", null, "now()", "'사유'")).as("취소가 아닌데 취소 시각 있음")
				.hasMessageContaining("ck_charge_canceled");
		assertThatThrownBy(() -> insertCharge("TENANT_REPORTED", "now()", null, null, null)).as("신고 시각 없음")
				.hasMessageContaining("ck_charge_tenant_reported_at");
		assertThatCode(() -> insertCharge("CANCELED", null, null, "now()", "'발행 전 취소'")).doesNotThrowAnyException();
		assertThatThrownBy(() -> jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, created_at, updated_at) values (?, ?, '2026-11-02', '2026-11-05', 0, "
				+ "'SCHEDULED', 'AUTO', now(), now())", landlordA, leaseA))
				.hasMessageContaining("ck_charge_billing_month");
	}

	@Test
	void leaseWithChargeCannotBeDeleted() {
		charge(landlordA, leaseA, "2026-11-01", "ISSUED");

		assertThatThrownBy(() -> jdbc.update("delete from lease where id = ?", leaseA))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_charge_lease");
	}

	@Test
	void paymentCorrectionMustPointToSameCharge() {
		long charge1 = charge(landlordA, leaseA, "2026-11-01", "ISSUED");
		long charge2 = charge(landlordA, leaseA, "2026-12-01", "ISSUED");
		long payment = payment(landlordA, charge1, null);

		assertThatThrownBy(() -> payment(landlordA, charge2, payment)).as("다른 청구의 기록을 정정할 수 없다")
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_payment_record_replaces");
		assertThatCode(() -> payment(landlordA, charge1, payment)).doesNotThrowAnyException();
		assertThatThrownBy(() -> jdbc.update("update payment_record set deleted_at = now() where id = ?", payment))
				.as("지운 사유 필수").hasMessageContaining("ck_payment_record_deleted");
		assertThatThrownBy(() -> jdbc.update("update payment_record set amount = 0 where id = ?", payment))
				.hasMessageContaining("ck_payment_record_amount");
	}

	@Test
	void purgingLandlordDeletesChargesAndChildrenButLeavesOthers() {
		long chargeA = charge(landlordA, leaseA, "2026-11-01", "ISSUED");
		jdbc.update("insert into charge_item (landlord_id, charge_id, type, label, amount, created_at, updated_at) "
				+ "values (?, ?, 'RENT', '월세', 500000, now(), now())", landlordA, chargeA);
		payment(landlordA, chargeA, null);
		jdbc.update("insert into charge_status_history (landlord_id, charge_id, to_status, actor_type, created_at) "
				+ "values (?, ?, 'SCHEDULED', 'SYSTEM', now())", landlordA, chargeA);
		jdbc.update(
				"insert into charge_item_history (landlord_id, charge_id, charge_item_id, action, item_type, label, "
						+ "after_amount, actor_type, created_at) values (?, ?, 1, 'ADD', 'RENT', '월세', 500000, 'SYSTEM', now())",
				landlordA, chargeA);
		charge(landlordB, leaseB, "2026-11-01", "ISSUED");

		// 임대인 파기(WithdrawnAccountPurger)와 같은 한 문장: 청구 → 계약 FK(NO ACTION)는 문장 끝에 검사해 막지
		// 않는다
		jdbc.update("delete from landlord where id = ?", landlordA);

		for (String table : new String[]{"charge", "charge_item", "payment_record", "charge_status_history",
				"charge_item_history", "lease"}) {
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where landlord_id = ?", Integer.class,
					landlordA)).as(table).isZero();
		}
		assertThat(jdbc.queryForObject("select count(*) from charge where landlord_id = ?", Integer.class, landlordB))
				.isEqualTo(1);
	}

	@Test
	void entitiesMapToSchemaAndVersionIncrements() {
		Instant now = Instant.parse("2026-11-01T00:00:00Z");
		Charge saved = transaction.execute(status -> {
			Charge charge = charges.saveAndFlush(Charge.create(landlordA, leaseA, YearMonth.of(2026, 11),
					LocalDate.of(2026, 11, 5), 550_000, ChargeSource.AUTO));
			statusHistories.save(ChargeStatusHistory.of(charge, charge.created(), ChangeActor.SYSTEM, null, now));
			ChargeItem item = items.saveAndFlush(ChargeItem.of(charge, ChargeItemType.RENT, "월세", 500_000));
			itemHistories.save(ChargeItemHistory.added(item, ChangeActor.SYSTEM, now));
			return charge;
		});
		assertThat(saved.getVersion()).isZero();

		transaction.executeWithoutResult(status -> {
			Charge charge = charges.findByIdAndLandlordId(saved.getId(), landlordA).orElseThrow();
			statusHistories.save(
					ChargeStatusHistory.of(charge, charge.issue(now), ChangeActor.SYSTEM, "자동 발행 — 알림 발송은 M5", now));
		});

		Charge reloaded = charges.findByIdAndLandlordId(saved.getId(), landlordA).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.ISSUED);
		assertThat(reloaded.getVersion()).isEqualTo(1);
		assertThat(reloaded.getBillingMonth()).isEqualTo(YearMonth.of(2026, 11));
		assertThat(charges.findByIdAndLandlordId(saved.getId(), landlordB)).as("남의 청구는 없다").isEmpty();
		assertThat(statusHistories.findByChargeIdAndLandlordIdOrderByIdAsc(saved.getId(), landlordA))
				.extracting(ChargeStatusHistory::getToStatus)
				.containsExactly(ChargeStatus.SCHEDULED, ChargeStatus.ISSUED);
		assertThat(itemHistories.findByChargeIdAndLandlordIdOrderByIdAsc(saved.getId(), landlordA)).singleElement()
				.satisfies(h -> assertThat(h.getAfterAmount()).isEqualTo(500_000L));
		assertThat(jdbc.queryForObject("select issue_days_before from lease where id = ?", Integer.class, leaseA))
				.as("기존 계약은 기본값 7일").isEqualTo(7);
	}

	private void insertCharge(String status, String issuedAt, String paidAt, String canceledAt, String cancelReason) {
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source, "
				+ "issued_at, paid_at, canceled_at, cancel_reason, created_at, updated_at) values (?, ?, "
				+ "'2026-10-01', '2026-10-05', 1, ?, 'AUTO', " + issuedAt + ", " + paidAt + ", " + canceledAt + ", "
				+ cancelReason + ", now(), now())", landlordA, leaseA, status);
	}

	private long charge(long landlordId, long leaseId, String month, String status) {
		return insert(
				"insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source, "
						+ "issued_at, created_at, updated_at) values (?, ?, cast(? as date), cast(? as date) + 4, 550000, ?, "
						+ "'AUTO', now(), now(), now()) returning id",
				landlordId, leaseId, month, month, status);
	}

	private long payment(long landlordId, long chargeId, Long replacesId) {
		return insert("insert into payment_record (landlord_id, charge_id, amount, paid_on, source, replaces_id, "
				+ "created_at, updated_at) values (?, ?, 100000, '2026-11-03', 'MANUAL', ?, now(), now()) returning id",
				landlordId, chargeId, replacesId);
	}

	private long landlord(String email) {
		return insert("insert into landlord (auth_provider, email, password_hash, name, created_at, updated_at) "
				+ "values ('LOCAL', ?, '{bcrypt}x', '임대인', now(), now()) returning id", email);
	}

	private long lease(long landlordId, String hashChar) {
		long property = insert("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", landlordId);
		long unit = insert("insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
				+ "values (?, ?, '101호', 'ONE_ROOM', now(), now()) returning id", landlordId, property);
		long tenant = insert(
				"insert into tenant (landlord_id, name_enc, phone_enc, phone_hash, created_at, updated_at) "
						+ "values (?, " + CIPHER + ", " + CIPHER + ", ?, now(), now()) returning id",
				landlordId, hashChar.repeat(64));
		return insert("insert into lease (landlord_id, unit_id, tenant_id, deposit, due_day, start_date, end_date, "
				+ "status, bank_name, account_no_enc, account_holder_enc, created_at, updated_at) values (?, ?, ?, 0, 5, "
				+ "'2026-01-01', '2027-01-01', 'ACTIVE', '국민은행', " + CIPHER + ", " + CIPHER
				+ ", now(), now()) returning id", landlordId, unit, tenant);
	}

	private long insert(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

}
