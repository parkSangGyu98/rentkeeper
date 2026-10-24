package com.rentkeeper.demo.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.stereotype.Component;

import com.rentkeeper.billing.application.ChargeQueryService;
import com.rentkeeper.billing.application.ManualChargeService;
import com.rentkeeper.billing.application.PaymentService;
import com.rentkeeper.billing.application.PaymentService.PaymentInput;
import com.rentkeeper.billing.application.TenantReportService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantAccessService;
import com.rentkeeper.lease.application.TenantLinkService;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.PrivateMemo;
import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.application.PropertyViews.PropertyInput;
import com.rentkeeper.property.application.PropertyViews.UnitInput;
import com.rentkeeper.property.domain.UnitType;

/**
 * 데모 계정에 가짜 데이터를 넣는다 (M6 결정 3). 실제 서비스 코드(등록·청구·입금·신고)를 그대로 거쳐 만든다: 화면에서 하는 것과
 * 같은 상태와 이력이 남는다. 이름·전화번호·계좌는 모두 지어낸 값이다.
 * <ul>
 * <li>건물 2개, 호실 6개(공실 2)</li>
 * <li>101호: 지난달 다 받음, 이번 달 받을 차례(납부일이 지났으면 납부일 지남)</li>
 * <li>102호: 이번 달을 세입자가 "입금했어요"로 알림(확인 기다림)</li>
 * <li>103호: 지난달 일부만 받아 납부일 지남</li>
 * <li>201호: 약 60일 뒤 끝나는 계약</li>
 * </ul>
 */
@Component
class DemoSeeder {

	private final PropertyService propertyService;

	private final LeaseService leaseService;

	private final ManualChargeService manualCharges;

	private final ChargeQueryService chargeQuery;

	private final PaymentService payments;

	private final TenantLinkService tenantLinks;

	private final TenantAccessService tenantAccess;

	private final TenantReportService tenantReports;

	private final Clock clock;

	DemoSeeder(PropertyService propertyService, LeaseService leaseService, ManualChargeService manualCharges,
			ChargeQueryService chargeQuery, PaymentService payments, TenantLinkService tenantLinks,
			TenantAccessService tenantAccess, TenantReportService tenantReports, Clock clock) {
		this.propertyService = propertyService;
		this.leaseService = leaseService;
		this.manualCharges = manualCharges;
		this.chargeQuery = chargeQuery;
		this.payments = payments;
		this.tenantLinks = tenantLinks;
		this.tenantAccess = tenantAccess;
		this.tenantReports = tenantReports;
		this.clock = clock;
	}

	/** @return 101호 세입자 링크의 원문 (데모 시작 화면에 한 번만 보인다) */
	String seed(long landlordId) {
		LocalDate today = LocalDate.now(clock);
		YearMonth thisMonth = YearMonth.from(today);
		long villa = propertyService.create(landlordId, new PropertyInput("예시빌라", "서울시 예시구 예시로 1", null));
		long shop = propertyService.create(landlordId, new PropertyInput("샘플상가", "서울시 예시구 예시로 2", null));
		long u101 = unit(landlordId, villa, "101호", UnitType.ONE_ROOM);
		long u102 = unit(landlordId, villa, "102호", UnitType.ONE_ROOM);
		long u103 = unit(landlordId, villa, "103호", UnitType.TWO_ROOM);
		long u201 = unit(landlordId, villa, "201호", UnitType.TWO_ROOM);
		unit(landlordId, villa, "202호", UnitType.ONE_ROOM);
		unit(landlordId, shop, "1층", UnitType.STORE);

		long lease101 = lease(landlordId, u101, "김예시", "01000000001", thisMonth.minusMonths(2).atDay(1),
				thisMonth.plusMonths(10).atDay(1), 5, 500_000, 50_000);
		long last101 = charge(landlordId, lease101, thisMonth.minusMonths(1), 500_000, 50_000);
		pay(landlordId, last101, 550_000, thisMonth.minusMonths(1).atDay(5));
		charge(landlordId, lease101, thisMonth, 500_000, 50_000);

		long lease102 = lease(landlordId, u102, "이샘플", "01000000002", thisMonth.minusMonths(1).atDay(1),
				thisMonth.plusMonths(11).atDay(1), 25, 450_000, 0);
		long this102 = charge(landlordId, lease102, thisMonth, 450_000, 0);
		String link102 = tenantLinks.issue(landlordId, lease102, null).rawToken();
		long link102Id = tenantAccess.open(link102).orElseThrow().tokenId();
		tenantReports.report(link102Id, landlordId, lease102, this102);

		long lease103 = lease(landlordId, u103, "박데모", "01000000003", thisMonth.minusMonths(3).atDay(1),
				thisMonth.plusMonths(9).atDay(1), 10, 600_000, 40_000);
		long last103 = charge(landlordId, lease103, thisMonth.minusMonths(1), 600_000, 40_000);
		pay(landlordId, last103, 300_000, thisMonth.minusMonths(1).atDay(10));

		long lease201 = lease(landlordId, u201, "최견본", "01000000004", thisMonth.minusMonths(11).atDay(1),
				today.plusDays(60), 15, 550_000, 30_000);
		charge(landlordId, lease201, thisMonth, 550_000, 30_000);

		return tenantLinks.issue(landlordId, lease101, null).rawToken();
	}

	private long unit(long landlordId, long propertyId, String name, UnitType type) {
		return propertyService.addUnit(landlordId, propertyId, new UnitInput(name, type, null));
	}

	private long lease(long landlordId, long unitId, String tenantName, String phone, LocalDate start, LocalDate end,
			int dueDay, long rent, long fee) {
		LeaseTerms terms = new LeaseTerms(10_000_000, dueDay, start, end, "예시은행", new AccountNumber("1002003004005"),
				new PersonName("데모 임대인"), "데모용 가짜 계약입니다");
		return leaseService.register(landlordId, new LeaseRegistration(unitId, new PersonName(tenantName),
				PhoneNumber.parse(phone), new PrivateMemo("데모용 가짜 세입자"), rent, fee, terms, false));
	}

	private long charge(long landlordId, long leaseId, YearMonth month, long rent, long fee) {
		return manualCharges.create(landlordId, leaseId, month, rent, fee);
	}

	private void pay(long landlordId, long chargeId, long amount, LocalDate paidOn) {
		long version = chargeQuery.detail(landlordId, chargeId).version();
		payments.record(landlordId, chargeId, version, new PaymentInput(amount, paidOn, "데모 입금", false));
	}

}
