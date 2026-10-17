package com.rentkeeper.auth.settings;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.rentkeeper.auth.lock.LoginLockService;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 민감 작업(비밀번호·이메일 바꾸기) 전에 지금 비밀번호를 다시 확인한다 (M1-b 재인증).
 * <p>
 * 로그인과 같은 규칙을 쓴다: 잠겨 있어도 비밀번호 비교는 먼저 하고, 틀리면 로그인 실패와 같은 횟수에 더한다 (ADR-012). 세션을
 * 가로챈 사람이 이 화면들에서 비밀번호를 맞춰 보는 것을 막기 위해서다.
 */
@Component
public class CurrentPasswordVerifier {

	public enum Result {

		OK,

		/** 여러 번 틀려 잠김 (15분) */
		LOCKED,

		/** 지금 비밀번호가 틀림 (실패 횟수에 더함) */
		WRONG

	}

	private final LandlordService landlordService;

	private final PasswordEncoder passwordEncoder;

	private final LoginLockService lockService;

	public CurrentPasswordVerifier(LandlordService landlordService, PasswordEncoder passwordEncoder,
			LoginLockService lockService) {
		this.landlordService = landlordService;
		this.passwordEncoder = passwordEncoder;
		this.lockService = lockService;
	}

	public Result verify(long landlordId, String currentPassword) {
		String currentHash = landlordService.findPasswordHash(landlordId).orElseThrow();
		boolean matches = passwordEncoder.matches(currentPassword, currentHash);
		if (lockService.isLocked(landlordId)) {
			return Result.LOCKED;
		}
		if (!matches) {
			// 별도 트랜잭션으로 저장된다 (호출한 쪽이 롤백돼도 남음)
			lockService.recordFailure(landlordId);
			return Result.WRONG;
		}
		return Result.OK;
	}

}
