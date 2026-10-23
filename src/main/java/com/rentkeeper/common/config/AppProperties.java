package com.rentkeeper.common.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 앱 설정 (PRD 13장: 설정값은 @ConfigurationProperties로 타입 안전하게).
 *
 * @param baseUrl
 *            메일 링크를 만들 때 쓰는 주소 (환경변수 APP_BASE_URL, local 프로필은
 *            http://localhost:8080). 요청의 Host 헤더로 링크를 만들지 않는다: 공격자가 Host를 바꿔
 *            보내면 재설정 링크가 공격자 사이트를 가리킬 수 있다(비밀번호 재설정 링크 오염).
 * @param rememberMeKey
 *            remember-me 키 (환경변수 APP_REMEMBER_ME_KEY). 검증은 RememberMeKey가 한다
 *            (ADR-013)
 */
@ConfigurationProperties("app")
public record AppProperties(URI baseUrl, String rememberMeKey) {

	public AppProperties {
		if (baseUrl == null) {
			throw new IllegalStateException(
					"app.base-url(환경변수 APP_BASE_URL)이 필요합니다. 예: https://rentkeeper.example.com");
		}
	}

	/** 기준 주소 + 경로 + ?token=... (토큰은 Base64URL이라 따로 인코딩할 필요가 없다) */
	public String tokenLink(String path, String token) {
		return base() + path + "?token=" + token;
	}

	/** 세입자 링크: 기준 주소 + /t/{토큰} (M4, ADR-026). 토큰이 경로에 있으므로 이 주소는 로그에 남기지 않는다 */
	public String tenantLink(String token) {
		return base() + "/t/" + token;
	}

	private String base() {
		return baseUrl.toString().replaceAll("/+$", "");
	}

}
