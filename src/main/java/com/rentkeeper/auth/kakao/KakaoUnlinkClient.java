package com.rentkeeper.auth.kakao;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.web.client.RestClientResponseException;

/**
 * 카카오 연결 끊기(unlink) 호출 (ADR-015). 서비스 앱 어드민 키로 회원번호를 지정해 끊는다: 액세스 토큰을 보관하지
 * 않으므로(ADR-014) 탈퇴 시점이나 파기 배치에서 언제든 다시 시도할 수 있다.
 * <p>
 * 어드민 키는 서버에서만 쓰고 로그·예외 메시지에 넣지 않는다. 회원번호도 개인정보라 로그에는 landlord id만 남긴다 (호출한 쪽).
 */
@Component
public class KakaoUnlinkClient {

	private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkClient.class);

	private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d{1,9})");

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
	 * 예외를 던지지 않고 결과 종류를 돌려준다.
	 * <p>
	 * 실패하면 HTTP 상태와 카카오 에러 응답의 숫자 {@code code}만 WARN 로그에 남긴다. 응답 본문 전체와
	 * {@code msg}는 회원 정보가 섞일 가능성을 배제할 수 없어 남기지 않는다. 이미 끊긴 회원을 다시 끊을 때의 실제 응답을 이 로그로
	 * 확인한다 (M1-c 결정 4).
	 */
	public KakaoUnlinkResult unlink(String kakaoId) {
		if (!StringUtils.hasText(properties.adminKey())) {
			log.warn("kakao unlink failed: admin key is not configured");
			return KakaoUnlinkResult.CONFIG;
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("target_id_type", "user_id");
		form.add("target_id", kakaoId);
		try {
			restClient.post().uri(properties.unlinkUri())
					.header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
			return KakaoUnlinkResult.SUCCESS;
		} catch (RestClientResponseException e) {
			int status = e.getStatusCode().value();
			log.warn("kakao unlink failed: status={} kakaoCode={}", status,
					kakaoErrorCode(e.getResponseBodyAsString()));
			return classify(status);
		} catch (RestClientException e) {
			// 시간 초과·연결 실패 등. 요청 헤더(어드민 키)가 메시지에 섞일 수 있어 예외 종류만 남긴다
			log.warn("kakao unlink failed: {}", e.getClass().getSimpleName());
			return KakaoUnlinkResult.TRANSIENT;
		}
	}

	static KakaoUnlinkResult classify(int status) {
		if (status == 401 || status == 403) {
			return KakaoUnlinkResult.CONFIG;
		}
		if (status == 429 || status >= 500) {
			return KakaoUnlinkResult.TRANSIENT;
		}
		return KakaoUnlinkResult.REJECTED;
	}

	/** 카카오 에러 응답 {"msg": "...", "code": -101}에서 숫자 code만 꺼낸다. 없으면 "none" */
	static String kakaoErrorCode(String body) {
		Matcher matcher = ERROR_CODE.matcher(body == null ? "" : body);
		return matcher.find() ? matcher.group(1) : "none";
	}

}
