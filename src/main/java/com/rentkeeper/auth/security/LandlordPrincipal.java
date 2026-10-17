package com.rentkeeper.auth.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * 로그인한 임대인. 세션(Spring Session JDBC)에 JDK 직렬화로 저장되므로 작은 값만 담는다 (ADR-005).
 * <p>
 * {@link #getUsername()}은 이메일이 아니라 landlord id를 돌려준다. 세션 테이블의 PRINCIPAL_NAME이 이
 * 값이 되어, 이메일이 바뀌거나(M1-b) 이메일이 없는 카카오 계정(M1-c)이어도 "이 사용자의 세션"을 정확히 찾을 수 있다. 카카오
 * 로그인(M1-c)도 이 타입을 쓰도록 OAuth2User를 함께 구현한다. 로그인 방식과 관계없이 {@link #getName()}과
 * {@link #getUsername()}은 같은 landlord id다.
 */
public final class LandlordPrincipal implements UserDetails, OAuth2User, CredentialsContainer {

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

	/** 비밀번호 없이 만든다 (카카오 로그인, 가입 직후 로그인 등) */
	public static LandlordPrincipal of(LandlordAccount account) {
		return new LandlordPrincipal(account.id(), account.email(), account.name(),
				account.status() == LandlordStatus.ACTIVE, null);
	}

	public long getLandlordId() {
		return landlordId;
	}

	/** OAuth2User: 세션 principal 이름. 이메일 로그인과 같은 landlord id */
	@Override
	public String getName() {
		return String.valueOf(landlordId);
	}

	/**
	 * OAuth2User: 카카오가 준 사용자 정보는 담지 않는다 (세션에 개인정보를 남기지 않기 위해, ADR-014). 우리 id만 둔다.
	 */
	@Override
	public Map<String, Object> getAttributes() {
		return Map.of("landlordId", landlordId);
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
