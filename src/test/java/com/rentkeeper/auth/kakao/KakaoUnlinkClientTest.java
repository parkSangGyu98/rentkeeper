package com.rentkeeper.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 연결 끊기 응답 분류와 로그에 남기는 카카오 에러 코드 추출 (ADR-015).
 */
class KakaoUnlinkClientTest {

	@Test
	void classifiesStatus() {
		assertThat(KakaoUnlinkClient.classify(401, "-401")).isEqualTo(KakaoUnlinkResult.CONFIG);
		assertThat(KakaoUnlinkClient.classify(403, "none")).isEqualTo(KakaoUnlinkResult.CONFIG);
		assertThat(KakaoUnlinkClient.classify(429, "none")).isEqualTo(KakaoUnlinkResult.TRANSIENT);
		assertThat(KakaoUnlinkClient.classify(500, "none")).isEqualTo(KakaoUnlinkResult.TRANSIENT);
		assertThat(KakaoUnlinkClient.classify(503, "-1")).isEqualTo(KakaoUnlinkResult.TRANSIENT);
		assertThat(KakaoUnlinkClient.classify(400, "-2")).isEqualTo(KakaoUnlinkResult.REJECTED);
		assertThat(KakaoUnlinkClient.classify(404, "none")).isEqualTo(KakaoUnlinkResult.REJECTED);
	}

	@Test
	void notLinkedUserIsSuccessOnlyFor400WithCodeMinus101() {
		// 이미 끊김 또는 연결된 적 없음: 끊을 연결이 없다 (실제 카카오로 확인, ADR-015)
		assertThat(KakaoUnlinkClient.classify(400, "-101")).isEqualTo(KakaoUnlinkResult.SUCCESS);
		// 경계: 코드가 없거나 다른 상태의 -101은 안전하게 실패로
		assertThat(KakaoUnlinkClient.classify(400, "none")).isEqualTo(KakaoUnlinkResult.REJECTED);
		assertThat(KakaoUnlinkClient.classify(404, "-101")).isEqualTo(KakaoUnlinkResult.REJECTED);
		assertThat(KakaoUnlinkClient.classify(500, "-101")).isEqualTo(KakaoUnlinkResult.TRANSIENT);
		assertThat(KakaoUnlinkClient.kakaoErrorCode("{\"code\": -1012}")).as("-101로 시작하는 다른 코드").isEqualTo("-1012");
	}

	@Test
	void extractsOnlyNumericKakaoErrorCode() {
		assertThat(KakaoUnlinkClient.kakaoErrorCode("{\"msg\": \"anything 12345\", \"code\": -101}")).isEqualTo("-101");
		assertThat(KakaoUnlinkClient.kakaoErrorCode("<html>error</html>")).isEqualTo("none");
		assertThat(KakaoUnlinkClient.kakaoErrorCode(null)).isEqualTo("none");
	}

	@Test
	void missingAdminKeyIsConfigError() {
		KakaoProperties noKey = new KakaoProperties(null, null, null, "a", "t", "u", "http://127.0.0.1:1/unlink",
				List.of());
		assertThat(new KakaoUnlinkClient(noKey).unlink("1")).isEqualTo(KakaoUnlinkResult.CONFIG);
	}

	@Test
	void connectionFailureIsTransient() {
		// 1번 포트는 열려 있지 않다: 연결 실패
		KakaoProperties unreachable = new KakaoProperties("id", "secret", "admin", "a", "t", "u",
				"http://127.0.0.1:1/v1/user/unlink", List.of());
		assertThat(new KakaoUnlinkClient(unreachable).unlink("1")).isEqualTo(KakaoUnlinkResult.TRANSIENT);
	}

}
