package com.rentkeeper.demo.application;

import java.time.Instant;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.notification.application.NotificationPreferences;
import com.rentkeeper.notification.application.NotificationPreferences.Settings;

/**
 * 방문자용 임시 데모 계정 (M6 결정 3, ADR-031). 계정을 만들고 가짜 데이터를 넣는다. 계정 만들기(상한 확인)와 데이터 넣기는
 * 따로 트랜잭션이다: 넣다 실패하면 빈 데모 계정이 남지만 만료되면 함께 지워진다.
 * <p>
 * 데모 계정은 세입자 안내 문자 설정을 끈 채로 시작한다. 이메일은 인증하지 않은 가짜 주소라 메일도 받지 않는다.
 */
@Service
public class DemoService {

	private final DemoProperties properties;

	private final LandlordService landlordService;

	private final NotificationPreferences preferences;

	private final DemoSeeder seeder;

	private final PasswordEncoder passwordEncoder;

	DemoService(DemoProperties properties, LandlordService landlordService, NotificationPreferences preferences,
			DemoSeeder seeder, PasswordEncoder passwordEncoder) {
		this.properties = properties;
		this.landlordService = landlordService;
		this.preferences = preferences;
		this.seeder = seeder;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * 새 데모 계정
	 *
	 * @param tenantLinkToken
	 *            세입자 화면을 바로 볼 수 있는 링크 원문 (시작 화면에만 보이고 저장하지 않는다)
	 */
	public record DemoStart(long landlordId, String email, Instant expiresAt, String tenantLinkToken) {

		@Override
		public String toString() {
			return "DemoStart[landlordId=" + landlordId + ", expiresAt=" + expiresAt + "]";
		}

	}

	public boolean enabled() {
		return properties.enabled();
	}

	/**
	 * @throws LandlordService.DemoCapacityException
	 *             데모 계정이 상한만큼 있을 때
	 */
	public DemoStart start() {
		// 비밀번호로는 로그인할 수 없다: 아무도 모르는 값의 해시
		String unusablePassword = passwordEncoder.encode(SecureTokens.newToken());
		long landlordId = landlordService.registerDemo(unusablePassword, properties.lifetime(),
				properties.maxAccounts());
		preferences.save(landlordId, new Settings(true, true, true, false));
		String token = seeder.seed(landlordId);
		LandlordAccount account = landlordService.findAccount(landlordId).orElseThrow();
		return new DemoStart(landlordId, account.email(), account.demoExpiresAt(), token);
	}

	/** 로그인한 계정이 데모면 지워질 시각 */
	public Optional<Instant> demoExpiresAt(long landlordId) {
		return landlordService.findAccount(landlordId).map(LandlordAccount::demoExpiresAt);
	}

}
