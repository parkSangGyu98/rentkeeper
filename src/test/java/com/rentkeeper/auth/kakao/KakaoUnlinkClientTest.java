package com.rentkeeper.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 연결 끊기 응답 분류와 로그에 남기는 카카오 에러 코드 추출 (ADR-015).
 */
class KakaoUnlinkClientTest {

	@Test
	void classifiesStatus() {
		assertThat(KakaoUnlinkClient.classify(401)).isEqualTo(KakaoUnlinkResult.CONFIG);
		assertThat(KakaoUnlinkClient.classify(403)).isEqualTo(KakaoUnlinkResult.CONFIG);
		assertThat(KakaoUnlinkClient.classify(429)).isEqualTo(KakaoUnlinkResult.TRANSIENT);
		assertThat(KakaoUnlinkClient.classify(500)).isEqualTo(KakaoUnlinkResult.TRANSIENT);
		assertThat(KakaoUnlinkClient.classify(503)).isEqualTo(KakaoUnlinkResult.TRANSIENT);
		assertThat(KakaoUnlinkClient.classify(400)).isEqualTo(KakaoUnlinkResult.REJECTED);
		assertThat(KakaoUnlinkClient.classify(404)).isEqualTo(KakaoUnlinkResult.REJECTED);
	}

	@Test
	void extractsOnlyNumericKakaoErrorCode() {
		assertThat(KakaoUnlinkClient.kakaoErrorCode("{\"msg\": \"anything 12345\", \"code\": -101}")).isEqualTo("-101");
		assertThat(KakaoUnlinkClient.kakaoErrorCode("<html>error</html>")).isEqualTo("none");
		assertThat(KakaoUnlinkClient.kakaoErrorCode(null)).isEqualTo("none");
	}

	@Test
	void missingAdminKeyIsConfigError() {
		KakaoProperties noKey = new KakaoProperties(null, null, null, "a", "t", "u", "http://127.0.0.1:1/unlink");
		assertThat(new KakaoUnlinkClient(noKey).unlink("1")).isEqualTo(KakaoUnlinkResult.CONFIG);
	}

	@Test
	void connectionFailureIsTransient() {
		// 1번 포트는 열려 있지 않다: 연결 실패
		KakaoProperties unreachable = new KakaoProperties("id", "secret", "admin", "a", "t", "u",
				"http://127.0.0.1:1/v1/user/unlink");
		assertThat(new KakaoUnlinkClient(unreachable).unlink("1")).isEqualTo(KakaoUnlinkResult.TRANSIENT);
	}

}
