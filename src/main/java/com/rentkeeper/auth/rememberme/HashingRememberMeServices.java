package com.rentkeeper.auth.rememberme;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;

import com.rentkeeper.common.crypto.SecureTokens;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그인 유지(remember-me 30일) (ADR-013). Spring Security 표준
 * PersistentTokenBasedRememberMeServices를 두 군데만 바꿨다.
 * <ol>
 * <li><strong>해시 비교</strong>: 쿠키의 토큰 원문을 SHA-256으로 바꿔 부모에게 넘긴다.
 * DB({@link HashingPersistentTokenRepository})에는 해시만 있으므로 해시끼리 비교된다. 토큰 회전·도난
 * 감지·만료 처리는 부모 로직을 그대로 쓴다.</li>
 * <li><strong>도난 감지 시 500 대신 안내</strong>: 부모는 이미 쓴(회전된) 토큰이 다시 오면 그 사용자의 토큰을 모두
 * 지우고 CookieTheftException을 던진다. Spring Security 7.1.1의
 * RememberMeAuthenticationFilter는 이 예외를 잡지 않아 500 오류 화면이 된다. 그래서 여기서 잡아 로그아웃
 * 상태로 진행시키고, 로그인 화면에 "보안을 위해 다시 로그인해 주세요"를 띄운다.</li>
 * </ol>
 */
public class HashingRememberMeServices extends PersistentTokenBasedRememberMeServices {

	public static final String COOKIE_NAME = "remember-me";

	public static final String PARAMETER = "remember-me";

	public static final Duration VALIDITY = Duration.ofDays(30);

	/** 도난 감지 후 로그인 화면에 안내를 띄우기 위해 세션에 남기는 표시 (LoginController가 읽고 지운다) */
	public static final String THEFT_NOTICE_SESSION_ATTRIBUTE = HashingRememberMeServices.class.getName() + ".THEFT";

	private static final Logger log = LoggerFactory.getLogger(HashingRememberMeServices.class);

	public HashingRememberMeServices(String key, UserDetailsService userDetailsService,
			HashingPersistentTokenRepository tokenRepository) {
		super(key, userDetailsService, tokenRepository);
		setCookieName(COOKIE_NAME);
		setParameter(PARAMETER);
		setTokenValiditySeconds((int) VALIDITY.toSeconds());
		// 세션 쿠키와 같은 기준: HTTPS로만, 다른 사이트에서 시작된 POST에는 보내지 않음 (HttpOnly는 부모가 항상 켠다)
		setUseSecureCookie(true);
		setCookieCustomizer(cookie -> cookie.setAttribute("SameSite", "Lax"));
	}

	@Override
	protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request,
			HttpServletResponse response) {
		if (cookieTokens.length == 2) {
			cookieTokens = new String[]{cookieTokens[0], SecureTokens.sha256Hex(cookieTokens[1])};
		}
		return super.processAutoLoginCookie(cookieTokens, request, response);
	}

	@Override
	public @Nullable Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
		try {
			return super.autoLogin(request, response);
		} catch (CookieTheftException e) {
			// 부모가 이미 쿠키를 지우고 이 사용자의 remember-me 토큰을 모두 삭제했다
			log.warn("remember-me 토큰 재사용 감지: 해당 사용자의 로그인 유지를 모두 해제함");
			request.getSession(true).setAttribute(THEFT_NOTICE_SESSION_ATTRIBUTE, Boolean.TRUE);
			return null;
		}
	}

}
