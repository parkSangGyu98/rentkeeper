package com.rentkeeper.tenantlink.web;

import java.util.regex.Pattern;

/**
 * 세입자 링크 경로의 토큰 가리기 (M4 추가 요구). 경로 /t/{토큰}에는 링크 원문이 들어 있으므로, 로그에 남기는 주소·메시지는
 * 반드시 이것을 거친다. 운영의 Caddy 접근 로그는 따로 가린다(progress "출시 전 필수").
 */
public final class TenantPaths {

	// 토큰 모양(Base64URL 43자)이면 어디에 있든 가린다. 형식이 틀린 토큰도 /t/ 다음 경로 조각은 가린다
	private static final Pattern TOKEN_LIKE = Pattern.compile("[A-Za-z0-9_-]{43}");

	private static final Pattern AFTER_T = Pattern.compile("/t/[^/?#]*");

	private TenantPaths() {
	}

	/** "/t/abc.../history" → "/t/***\/history" */
	public static String mask(String text) {
		if (text == null) {
			return "null";
		}
		return TOKEN_LIKE.matcher(AFTER_T.matcher(text).replaceAll("/t/***")).replaceAll("***");
	}

}
