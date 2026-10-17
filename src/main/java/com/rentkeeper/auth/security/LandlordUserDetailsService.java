package com.rentkeeper.auth.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.landlord.domain.EmailAddress.InvalidEmailException;
import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * 이메일 로그인 폼의 입력(이메일)으로 계정을 찾는다. 입력을 EmailAddress로 정규화하므로 대문자로 입력해도 로그인된다
 * (ADR-006).
 * <p>
 * 계정이 없으면 UsernameNotFoundException을 던지지만, Spring Security가 이를 비밀번호 오류와 같은
 * BadCredentialsException으로 바꾸고 응답 시간 차이도 줄여 준다 → 화면에서는 "이메일 또는 비밀번호가 맞지 않아요"
 * 하나로 안내한다.
 */
@Service
public class LandlordUserDetailsService implements UserDetailsService {

	private final LandlordService landlordService;

	public LandlordUserDetailsService(LandlordService landlordService) {
		this.landlordService = landlordService;
	}

	@Override
	public UserDetails loadUserByUsername(String rawEmail) {
		EmailAddress email;
		try {
			email = EmailAddress.of(rawEmail);
		} catch (InvalidEmailException e) {
			throw new UsernameNotFoundException("invalid email format");
		}
		return landlordService
				.findLocalCredentials(email).map(c -> new LandlordPrincipal(c.id(), c.email(), c.name(),
						c.status() == LandlordStatus.ACTIVE, c.passwordHash()))
				.orElseThrow(() -> new UsernameNotFoundException("landlord not found"));
	}

}
