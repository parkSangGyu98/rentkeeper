package com.rentkeeper.auth.security;

import java.util.function.Supplier;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;

import com.rentkeeper.auth.lock.LoginLockService;

/**
 * 이메일·비밀번호 로그인 (ADR-012). Spring Security 기본 DaoAuthenticationProvider 대신 쓴다.
 * <p>
 * 기본 구현은 잠긴 계정이면 비밀번호 검사(BCrypt) 전에 바로 실패시킨다. 그러면 잠긴 계정만 응답이 수백 ms 빨라져, 응답 시간으로
 * 계정이 있는지·잠겼는지 알 수 있다. 그래서 이 구현은
 * <ol>
 * <li>계정이 없어도 가짜 해시로 BCrypt 비교를 하고,</li>
 * <li>계정이 있으면 잠김 여부와 상관없이 <strong>항상 BCrypt 비교를 먼저</strong> 한 뒤 판단한다.</li>
 * </ol>
 * 실패 이유(없는 계정·틀린 비밀번호·잠김·탈퇴)와 관계없이 같은 BadCredentialsException을 던져 화면 안내도 같게 한다.
 * 잠긴 동안에는 맞는 비밀번호도 실패시킨다 (잠금 중에 비밀번호를 맞춰 볼 수 없게).
 * <p>
 * 이 클래스가 유일한 AuthenticationProvider 빈이라 Spring Security가 기본
 * DaoAuthenticationProvider를 따로 만들지 않는다. 만약 기본 구현이 함께 등록되면 이 구현의 실패 뒤에 기본 구현이
 * 다시 시도해 잠금이 우회되므로, 잠긴 계정 로그인 테스트로 이를 확인한다.
 */
@Component
public class LockAwareAuthenticationProvider implements AuthenticationProvider {

	private static final String FAILURE_MESSAGE = "Bad credentials";

	private final LandlordUserDetailsService userDetailsService;

	private final PasswordEncoder passwordEncoder;

	private final LoginLockService lockService;

	// 없는 계정일 때 시간을 맞추기 위한 가짜 해시 (처음 쓸 때 한 번 만든다)
	private final Supplier<String> dummyHash;

	public LockAwareAuthenticationProvider(LandlordUserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder, LoginLockService lockService) {
		this.userDetailsService = userDetailsService;
		this.passwordEncoder = passwordEncoder;
		this.lockService = lockService;
		this.dummyHash = SingletonSupplier.of(() -> passwordEncoder.encode("dummy-password-for-equal-timing"));
	}

	@Override
	public Authentication authenticate(Authentication authentication) {
		String email = authentication.getName();
		String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();

		LandlordPrincipal principal;
		try {
			principal = (LandlordPrincipal) userDetailsService.loadUserByUsername(email);
		} catch (UsernameNotFoundException e) {
			passwordEncoder.matches(password, dummyHash.get());
			throw new BadCredentialsException(FAILURE_MESSAGE);
		}

		boolean passwordMatches = passwordEncoder.matches(password, principal.getPassword());
		long landlordId = principal.getLandlordId();
		if (lockService.isLocked(landlordId)) {
			throw new BadCredentialsException(FAILURE_MESSAGE);
		}
		if (!passwordMatches) {
			lockService.recordFailure(landlordId);
			throw new BadCredentialsException(FAILURE_MESSAGE);
		}
		if (!principal.isEnabled()) {
			throw new BadCredentialsException(FAILURE_MESSAGE);
		}

		lockService.clear(landlordId);
		principal.eraseCredentials();
		return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
