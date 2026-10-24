package com.rentkeeper.common.config;

import java.net.URI;
import java.util.Locale;

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

	/** 기준 주소 + 경로 (알림 메일의 "바로 가기", 예: /app/dashboard) */
	public String link(String path) {
		return base() + path;
	}

	/** 세입자 링크: 기준 주소 + /t/{토큰} (M4, ADR-026). 토큰이 경로에 있으므로 이 주소는 로그에 남기지 않는다 */
	public String tenantLink(String token) {
		return base() + "/t/" + token;
	}

	/**
	 * 기준 주소의 출처 (scheme://host[:port], 기본 포트는 생략). 세입자 POST의 Origin 헤더와 비교한다 (M4 결정
	 * 8). 요청의 Host 헤더와 비교하지 않는다: 프록시 뒤에서 달라지고 바꿔 보낼 수 있다.
	 */
	public String origin() {
		String scheme = baseUrl.getScheme().toLowerCase(Locale.ROOT);
		int port = baseUrl.getPort();
		// 브라우저는 기본 포트(http 80, https 443)를 Origin에 적지 않는다
		boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80)
				|| (scheme.equals("https") && port == 443);
		return scheme + "://" + baseUrl.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
	}

	private String base() {
		return baseUrl.toString().replaceAll("/+$", "");
	}

}
