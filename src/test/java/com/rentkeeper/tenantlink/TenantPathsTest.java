package com.rentkeeper.tenantlink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.tenantlink.web.TenantPaths;

/** 로그에 남기는 세입자 링크 주소·메시지에서 토큰을 가린다 (M4 추가 요구) */
class TenantPathsTest {

	@Test
	void masksTheTokenSegmentAndTokenLikeText() {
		String token = SecureTokens.newToken();

		assertThat(TenantPaths.mask("/t/" + token)).isEqualTo("/t/***");
		assertThat(TenantPaths.mask("/t/" + token + "/history")).isEqualTo("/t/***/history");
		assertThat(TenantPaths.mask("/t/abc/charges/1")).as("형식이 틀린 토큰도 가린다").isEqualTo("/t/***/charges/1");
		assertThat(TenantPaths.mask("value '" + token + "' is bad")).doesNotContain(token);
		assertThat(TenantPaths.mask("/app/charges/1")).isEqualTo("/app/charges/1");
		assertThat(TenantPaths.mask(null)).isEqualTo("null");
	}

}
