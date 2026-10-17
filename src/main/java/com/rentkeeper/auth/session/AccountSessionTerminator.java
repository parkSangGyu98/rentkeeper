package com.rentkeeper.auth.session;

import java.util.Map;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import com.rentkeeper.auth.rememberme.HashingPersistentTokenRepository;

/**
 * 한 사용자의 로그인을 강제로 끝낸다: Spring Session의 세션과 remember-me 토큰을 <strong>항상
 * 함께</strong> 지운다.
 * <p>
 * 세션만 지우면 remember-me 쿠키로 바로 다시 들어올 수 있다. 세션의 principal 이름과 remember-me 토큰의
 * username이 모두 landlord id라서(ADR-005, ADR-013) id 하나로 둘 다 찾는다.
 */
@Component
public class AccountSessionTerminator {

	private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

	private final HashingPersistentTokenRepository rememberMeTokens;

	public AccountSessionTerminator(FindByIndexNameSessionRepository<? extends Session> sessionRepository,
			HashingPersistentTokenRepository rememberMeTokens) {
		this.sessionRepository = sessionRepository;
		this.rememberMeTokens = rememberMeTokens;
	}

	/** 모든 기기에서 로그아웃 (비밀번호 재설정 후) */
	public void terminateAll(long landlordId) {
		terminateAllExcept(landlordId, null);
	}

	/**
	 * 지금 쓰는 세션만 남기고 모두 로그아웃 (비밀번호 변경 후). remember-me 토큰은 지금 기기 것도 지운다 (새 비밀번호로 다시
	 * 로그인할 때 체크하면 새로 발급된다).
	 */
	public void terminateAllExcept(long landlordId, String keepSessionId) {
		String principalName = String.valueOf(landlordId);
		Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(principalName);
		for (String sessionId : sessions.keySet()) {
			if (!sessionId.equals(keepSessionId)) {
				sessionRepository.deleteById(sessionId);
			}
		}
		rememberMeTokens.removeUserTokens(principalName);
	}

}
