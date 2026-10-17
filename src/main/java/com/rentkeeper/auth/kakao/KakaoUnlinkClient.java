package com.rentkeeper.auth.kakao;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 연결 끊기(unlink) 호출 (ADR-015). 서비스 앱 어드민 키로 회원번호를 지정해 끊는다: 액세스 토큰을 보관하지
 * 않으므로(ADR-014) 탈퇴 시점이나 파기 배치에서 언제든 다시 시도할 수 있다.
 * <p>
 * 어드민 키는 서버에서만 쓰고 로그·예외 메시지에 넣지 않는다. 회원번호도 개인정보라 로그에는 landlord id만 남긴다 (호출한 쪽).
 */
@Component
public class KakaoUnlinkClient {

	private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkClient.class);

	private final KakaoProperties properties;

	private final RestClient restClient;

	public KakaoUnlinkClient(KakaoProperties properties) {
		this.properties = properties;
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/**
	 * @return 카카오가 성공(2xx)으로 답하면 true. 키가 없거나, 오류 응답·연결 실패면 false (예외를 던지지 않는다)
	 */
	public boolean unlink(String kakaoId) {
		if (!StringUtils.hasText(properties.adminKey())) {
			log.warn("kakao unlink skipped: admin key is not configured");
			return false;
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("target_id_type", "user_id");
		form.add("target_id", kakaoId);
		try {
			restClient.post().uri(properties.unlinkUri())
					.header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
			return true;
		} catch (RestClientException e) {
			// 응답 본문·요청 헤더(어드민 키)가 메시지에 섞일 수 있어 예외 종류만 남긴다
			log.warn("kakao unlink failed: {}", e.getClass().getSimpleName());
			return false;
		}
	}

}
