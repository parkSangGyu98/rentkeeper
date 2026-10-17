package com.rentkeeper.auth.kakao;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 카카오로 처음 온 사람이 가입 단계(/signup/kakao)를 마치기 전의 임시 로그인 정보 (ADR-014).
 * <p>
 * 동의 없이 계정이 생기지 않도록, 이 단계에서는 아직 landlord 행을 만들지 않는다. 권한은 ROLE_KAKAO_SIGNUP뿐이라
 * /app/**에 들어갈 수 없다. 가입을 마치면 LandlordPrincipal로 바뀐다. 세션에 직렬화되어 저장되므로 필요한 값만
 * 담는다.
 */
public final class KakaoSignupPrincipal implements OAuth2User, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	public static final String ROLE = "ROLE_KAKAO_SIGNUP";

	private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority(ROLE));

	private final KakaoProfileData profile;

	// 세션 principal 이름용 무작위 값. 회원번호를 쓰지 않는다 (아래 getName)
	private final String pendingId = UUID.randomUUID().toString();

	public KakaoSignupPrincipal(KakaoProfile profile) {
		this.profile = new KakaoProfileData(profile.kakaoId(), profile.email(), profile.emailTrusted(),
				profile.nickname());
	}

	public KakaoProfile profile() {
		return new KakaoProfile(profile.kakaoId(), profile.email(), profile.emailTrusted(), profile.nickname());
	}

	@Override
	public Map<String, Object> getAttributes() {
		return Map.of("id", profile.kakaoId());
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return AUTHORITIES;
	}

	/**
	 * 세션 principal 이름. landlord id(숫자)와 겹치지 않게 접두사를 붙인다.
	 * <p>
	 * Spring Session은 이 값을 spring_session.principal_name(인덱스가 걸린 평문 컬럼)에 저장하므로 회원번호
	 * 대신 무작위 값을 쓴다. 회원번호는 가입 처리에 필요해 세션 속성(이 객체를 직렬화한 값)에는 여전히 들어 있다 (ADR-014).
	 */
	@Override
	public String getName() {
		return "kakao-signup:" + pendingId;
	}

	// 로그에 회원번호가 찍히지 않게 한다
	@Override
	public String toString() {
		return "KakaoSignupPrincipal[" + pendingId + "]";
	}

	private record KakaoProfileData(String kakaoId, String email, boolean emailTrusted,
			String nickname) implements Serializable {
	}

}
