package com.rentkeeper.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청한 사람의 IP. 레이트 리미트의 기준이 된다.
 * <p>
 * <strong>운영 주의(출시 전 필수, docs/progress.md):</strong> 지금은
 * {@code getRemoteAddr()}를 그대로 쓴다. 운영에서 Caddy 같은 리버스 프록시 뒤에 두면 모든 요청이 프록시 IP로
 * 보여 전체 사용자가 같은 한도에 묶인다. M6에서 신뢰할 프록시를 정하고
 * {@code server.forward-headers-strategy}를 설정해야 한다. 그때 X-Forwarded-For를 아무
 * 요청에서나 믿으면 공격자가 IP를 마음대로 바꿀 수 있으므로, 이 클래스 한 곳만 고치면 되도록 IP 읽기를 모아 두었다.
 */
public final class ClientIp {

	private ClientIp() {
	}

	public static String of(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

}
