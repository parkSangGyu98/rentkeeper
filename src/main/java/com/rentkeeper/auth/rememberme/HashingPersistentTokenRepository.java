package com.rentkeeper.auth.rememberme;

import java.util.Date;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import com.rentkeeper.common.crypto.SecureTokens;

/**
 * remember-me 토큰 저장소. 표준 JdbcTokenRepositoryImpl에 저장을 맡기되, 토큰 값은
 * <strong>SHA-256으로 바꿔서</strong> 저장한다 (ADR-013). DB가 유출돼도 쿠키를 만들어 로그인할 수 없다.
 * <p>
 * 조회는 저장된 해시를 그대로 돌려준다. 비교할 때 쿠키의 원문을 해시하는 일은
 * {@link HashingRememberMeServices}가 한다.
 */
public class HashingPersistentTokenRepository implements PersistentTokenRepository {

	private final JdbcTokenRepositoryImpl delegate = new JdbcTokenRepositoryImpl();

	public HashingPersistentTokenRepository(DataSource dataSource) {
		delegate.setDataSource(dataSource);
		delegate.setCreateTableOnStartup(false); // 테이블은 Flyway(V6)가 만든다
	}

	@Override
	public void createNewToken(PersistentRememberMeToken token) {
		delegate.createNewToken(new PersistentRememberMeToken(token.getUsername(), token.getSeries(),
				SecureTokens.sha256Hex(token.getTokenValue()), token.getDate()));
	}

	@Override
	public void updateToken(String series, String tokenValue, Date lastUsed) {
		delegate.updateToken(series, SecureTokens.sha256Hex(tokenValue), lastUsed);
	}

	@Override
	public @Nullable PersistentRememberMeToken getTokenForSeries(String seriesId) {
		return delegate.getTokenForSeries(seriesId);
	}

	@Override
	public void removeUserTokens(String username) {
		delegate.removeUserTokens(username);
	}

}
