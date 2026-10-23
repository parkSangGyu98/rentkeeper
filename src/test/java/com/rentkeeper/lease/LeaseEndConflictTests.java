package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.ChargedMonths;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 계약 종료·나가는 날 단축이 이후 달 받을 월세를 취소하는 순간 같은 받을 월세에 입금이 겹치면(낙관적 락) 500이 아니라 다시 눌러
 * 달라는 안내가 나오고 계약은 그대로다 (M3-b 위험 항목). 겹치는 순간은 ChargedMonths를 대신해 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class LeaseEndConflictTests {

	private static final String RETRY = "같은 때에 받을 월세 기록이 바뀌어 저장하지 못했어요. 바뀐 것은 없어요. 잠시 뒤 한 번 더 눌러 주세요.";

	@MockitoBean
	ChargedMonths chargedMonths;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	LeaseService leaseService;

	@Autowired
	MutableClock clock;

	Session a;

	long lease;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		long property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, "
						+ "updated_at) values (?, ?, '101호', 'ONE_ROOM', now(), now()) returning id",
				Long.class, a.landlordId(), property);
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		lease = leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 0, terms, false));
		when(chargedMonths.latest(anyLong(), anyLong())).thenReturn(Optional.empty());
		when(chargedMonths.cancelAfter(anyLong(), anyLong(), any(), anyString()))
				.thenThrow(new ObjectOptimisticLockingFailureException("Charge", 1L));
	}

	@Test
	void endingShowsRetryGuideAndLeavesLeaseActive() throws Exception {
		String body = mockMvc.perform(
				post("/app/leases/" + lease + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2026-10-17"))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains(RETRY);
		assertThat(jdbc.queryForObject("select status from lease where id = ?", String.class, lease))
				.isEqualTo("ACTIVE");
	}

	@Test
	void shorteningShowsRetryGuideAndKeepsEndDate() throws Exception {
		String body = mockMvc
				.perform(post("/app/leases/" + lease).with(csrf()).cookie(a.cookie()).param("tenantName", "홍길동")
						.param("tenantPhone", "010-1111-2222").param("tenantMemo", "").param("deposit", "0")
						.param("firstMonthlyRent", "500000").param("firstMaintenanceFee", "0").param("dueDay", "5")
						.param("issueDaysBefore", "7").param("endDate", "2026-12-01").param("bankName", "국민은행")
						.param("accountNumber", "1234567890").param("accountHolder", "김임대").param("memo", ""))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains(RETRY);
		assertThat(jdbc.queryForObject("select end_date from lease where id = ?", LocalDate.class, lease))
				.isEqualTo(LocalDate.parse("2027-01-01"));
	}

}
