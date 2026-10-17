package com.rentkeeper.auth.rememberme;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * remember-me 자동 로그인용 사용자 조회. 이메일이 아니라 <strong>landlord id</strong>로 찾는다
 * (ADR-013).
 * <p>
 * remember-me 토큰에는 principal 이름(= landlord id, ADR-005)이 저장되고, 자동 로그인할 때 그 값으로
 * 사용자를 찾는다. 이메일로 찾는 LandlordUserDetailsService로는 찾을 수 없다.
 * <p>
 * 로그인 잠금은 반영하지 않는다 (ADR-012 B안: 잠금은 새 비밀번호 로그인만 막는다). 탈퇴 등 계정 상태는 반영한다.
 */
public class RememberMeUserDetailsService implements UserDetailsService {

	private final LandlordService landlordService;

	public RememberMeUserDetailsService(LandlordService landlordService) {
		this.landlordService = landlordService;
	}

	@Override
	public UserDetails loadUserByUsername(String landlordId) {
		long id;
		try {
			id = Long.parseLong(landlordId);
		} catch (NumberFormatException e) {
			throw new UsernameNotFoundException("invalid landlord id");
		}
		return landlordService.findAccount(id)
				.map(account -> new LandlordPrincipal(account.id(), account.email(), account.name(),
						account.status() == LandlordStatus.ACTIVE, null))
				.orElseThrow(() -> new UsernameNotFoundException("landlord not found"));
	}

}
