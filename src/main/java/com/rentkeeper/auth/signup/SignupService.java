package com.rentkeeper.auth.signup;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.verification.EmailVerificationService;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 이메일 회원가입. 비밀번호 규칙 확인과 해시는 여기(auth)에서 하고, landlord 모듈에는 해시만 넘긴다 (평문 비밀번호는 auth
 * 밖으로 나가지 않는다).
 */
@Service
public class SignupService {

	private final LandlordService landlordService;

	private final PasswordPolicy passwordPolicy;

	private final PasswordEncoder passwordEncoder;

	private final EmailVerificationService emailVerificationService;

	public SignupService(LandlordService landlordService, PasswordPolicy passwordPolicy,
			PasswordEncoder passwordEncoder, EmailVerificationService emailVerificationService) {
		this.landlordService = landlordService;
		this.passwordPolicy = passwordPolicy;
		this.passwordEncoder = passwordEncoder;
		this.emailVerificationService = emailVerificationService;
	}

	/**
	 * 계정을 만들고, 바로 로그인시킬 수 있도록 로그인 정보를 돌려준다.
	 *
	 * @throws EmailAddress.InvalidEmailException
	 *             이메일 형식이 잘못됐을 때
	 * @throws WeakPasswordException
	 *             비밀번호 규칙 위반
	 * @throws com.rentkeeper.landlord.application.DuplicateEmailException
	 *             이미 가입된 이메일
	 * @throws com.rentkeeper.landlord.domain.Landlord.InvalidNameException
	 *             이름이 비었거나 너무 길 때
	 */
	public LandlordPrincipal signup(String rawEmail, String password, String name) {
		EmailAddress email = EmailAddress.of(rawEmail);
		passwordPolicy.check(password, email).ifPresent(violation -> {
			throw new WeakPasswordException(violation);
		});
		long landlordId = landlordService.registerLocal(email, passwordEncoder.encode(password), name);
		// 인증 전에도 로그인은 된다. 인증 메일만 보내 두고, 화면에서는 안내 띠로 알린다
		emailVerificationService.sendVerification(landlordId);
		return new LandlordPrincipal(landlordId, email.value(), name.strip(), true, null);
	}

}
