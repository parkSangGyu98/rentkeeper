package com.rentkeeper.auth.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 계정 잠금 (ADR-012): 5회 연속 실패 → 15분 잠금, 잠금 중 연장 없음, 응답 구분 불가, 응답 시간 균일화, 기존 세션
 * 유지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class LoginLockTests {

	private static final String EMAIL = "kim@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	MutableClock clock;

	@Autowired
	LandlordService landlordService;

	@Autowired
	LoginLockService lockService;

	// 잠긴 계정에서도 BCrypt 비교가 일어나는지 호출 횟수로 확인한다
	@MockitoSpyBean
	PasswordEncoder passwordEncoder;

	long landlordId;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbcTemplate);
		landlordId = landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(PASSWORD), "김임대");
		landlordService.registerLocal(EmailAddress.of("other@example.com"), passwordEncoder.encode(PASSWORD), "다른사람");
	}

	@Test
	void fourFailuresThenSuccessClearsCounter() throws Exception {
		failTimes(4);

		login(EMAIL, PASSWORD).andExpect(redirectedUrl("/app")).andExpect(authenticated());
		assertThat(jdbcTemplate.queryForObject("select count(*) from login_failure", Integer.class)).isZero();
	}

	@Test
	void fifthFailureLocksForFifteenMinutesEvenForCorrectPassword() throws Exception {
		failTimes(5);

		assertThat(jdbcTemplate.queryForObject("select locked_until from login_failure where landlord_id = ?",
				Timestamp.class, landlordId).toInstant())
				.isEqualTo(MutableClockConfiguration.START.plus(Duration.ofMinutes(15)));
		login(EMAIL, PASSWORD).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());

		clock.advance(Duration.ofMinutes(15));
		login(EMAIL, PASSWORD).andExpect(redirectedUrl("/app")).andExpect(authenticated());
	}

	@Test
	void failuresDuringLockDoNotExtendIt() throws Exception {
		failTimes(5);
		clock.advance(Duration.ofMinutes(10));
		failTimes(3);
		clock.advance(Duration.ofMinutes(5));

		login(EMAIL, PASSWORD).andExpect(redirectedUrl("/app"));
	}

	@Test
	void afterLockExpiresCountingStartsOver() throws Exception {
		failTimes(5);
		clock.advance(Duration.ofMinutes(15));
		failTimes(4);

		assertThat(lockService.isLocked(landlordId)).isFalse();
		login(EMAIL, PASSWORD).andExpect(redirectedUrl("/app"));
	}

	@Test
	void lockedWrongAndUnknownAccountsLookTheSame() throws Exception {
		failTimes(5);

		String lockedCorrect = redirectOf(login(EMAIL, PASSWORD));
		String lockedWrong = redirectOf(login(EMAIL, "wrong-password"));
		String otherWrong = redirectOf(login("other@example.com", "wrong-password"));
		String unknown = redirectOf(login("nobody@example.com", PASSWORD));

		assertThat(List.of(lockedCorrect, lockedWrong, otherWrong, unknown)).containsOnly("/login?error");
	}

	@Test
	void passwordIsAlwaysCheckedSoTimingDoesNotRevealLockOrExistence() throws Exception {
		failTimes(5);

		clearInvocations(passwordEncoder);
		login(EMAIL, PASSWORD);
		verify(passwordEncoder, times(1)).matches(any(), any());

		clearInvocations(passwordEncoder);
		login("nobody@example.com", PASSWORD);
		verify(passwordEncoder, times(1)).matches(any(), any());
	}

	@Test
	void lockDoesNotEndExistingSession() throws Exception {
		MvcResult loggedIn = login(EMAIL, PASSWORD).andReturn();
		Cookie session = loggedIn.getResponse().getCookie("SESSION");

		failTimes(5); // 다른 곳(공격자)에서 잠금

		mockMvc.perform(get("/app").cookie(session)).andExpect(status().isOk());
	}

	@Test
	void concurrentFailuresAreCountedExactly() throws Exception {
		int threads = 10;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return lockService.recordFailure(landlordId);
				}));
			}
			start.countDown();
			int lockedNow = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					lockedNow++;
				}
			}
			assertThat(lockedNow).as("정확히 5번째 실패에서 한 번만 잠김").isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("select failed_count from login_failure", Integer.class))
					.isEqualTo(5);
		} finally {
			executor.shutdownNow();
		}
	}

	private void failTimes(int times) throws Exception {
		for (int i = 0; i < times; i++) {
			login(EMAIL, "wrong-password-" + i).andExpect(redirectedUrl("/login?error"));
		}
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/login").with(csrf()).param("email", email).param("password", password));
	}

	private static String redirectOf(ResultActions result) {
		return result.andReturn().getResponse().getRedirectedUrl();
	}

}
