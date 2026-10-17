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
 */
@ConfigurationProperties("app")
public record AppProperties(URI baseUrl) {

	public AppProperties {
		if (baseUrl == null) {
			throw new IllegalStateException(
					"app.base-url(환경변수 APP_BASE_URL)이 필요합니다. 예: https://rentkeeper.example.com");
		}
	}

	/** 기준 주소 + 경로 + ?token=... (토큰은 Base64URL이라 따로 인코딩할 필요가 없다) */
	public String tokenLink(String path, String token) {
		String base = baseUrl.toString().replaceAll("/+$", "");
		return base + path + "?token=" + token;
	}

}
