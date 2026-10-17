package com.rentkeeper.auth.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인한 임대인. 세션(Spring Session JDBC)에 JDK 직렬화로 저장되므로 작은 값만 담는다 (ADR-005).
 * <p>
 * {@link #getUsername()}은 이메일이 아니라 landlord id를 돌려준다. 세션 테이블의 PRINCIPAL_NAME이 이
 * 값이 되어, 이메일이 바뀌거나(M1-b) 이메일이 없는 카카오 계정(M1-c)이어도 "이 사용자의 세션"을 정확히 찾을 수 있다.
 * M1-c에서 OAuth2User도 구현해 로그인 방식과 관계없이 이 타입 하나를 쓴다.
 */
public final class LandlordPrincipal implements UserDetails, CredentialsContainer {

	@Serial
	private static final long serialVersionUID = 1L;

	private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_LANDLORD"));

	private final long landlordId;

	private final String email;

	private final String displayName;

	private final boolean active;

	// 로그인 검증 직후 eraseCredentials()로 지워 세션에 저장되지 않는다
	private String passwordHash;

	public LandlordPrincipal(long landlordId, String email, String displayName, boolean active, String passwordHash) {
		this.landlordId = landlordId;
		this.email = email;
		this.displayName = displayName;
		this.active = active;
		this.passwordHash = passwordHash;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public String getEmail() {
		return email;
	}

	public String getDisplayName() {
		return displayName;
	}

	@Override
	public String getUsername() {
		return String.valueOf(landlordId);
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return AUTHORITIES;
	}

	@Override
	public boolean isEnabled() {
		return active;
	}

	@Override
	public void eraseCredentials() {
		this.passwordHash = null;
	}

	@Override
	public String toString() {
		return "LandlordPrincipal[id=" + landlordId + "]";
	}

}
