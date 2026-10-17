package com.rentkeeper.auth.kakao;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 카카오 사용자 정보를 받아 우리 계정과 연결한다 (ADR-014).
 * <ul>
 * <li>사용 중(ACTIVE)인 카카오 계정이 있으면: 그 계정으로 로그인 (LandlordPrincipal, 이름 = landlord
 * id)</li>
 * <li>없으면: 가입 단계로 보낼 임시 로그인 정보 (KakaoSignupPrincipal). 계정은 아직 만들지 않는다</li>
 * </ul>
 * 회원번호로만 찾는다. 같은 이메일의 이메일 가입 계정이 있어도 합치지 않는다 (자동 병합 금지). 탈퇴 유예 중인 계정은 ACTIVE가
 * 아니므로 새 가입으로 이어진다 (M1-c 결정 1).
 */
@Component
public class KakaoOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

	private final LandlordService landlordService;

	public KakaoOAuth2UserService(LandlordService landlordService) {
		this.landlordService = landlordService;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) {
		KakaoProfile profile = KakaoProfile.from(delegate.loadUser(userRequest).getAttributes());
		return landlordService.findActiveKakaoAccount(profile.kakaoId()).<OAuth2User>map(LandlordPrincipal::of)
				.orElseGet(() -> new KakaoSignupPrincipal(profile));
	}

}
