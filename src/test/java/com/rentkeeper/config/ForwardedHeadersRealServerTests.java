package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 리버스 프록시(Caddy) 뒤의 접속 IP·HTTPS 판정 (M6 커밋 1, ADR-030). MockMvc는 Tomcat의
 * RemoteIpValve를 거치지 않으므로 실제 내장 Tomcat으로 확인한다. 운영 설정과 같은 값을 넣되, 신뢰할 프록시만 테스트가
 * 접속하는 127.0.0.1로 바꾼다.
 * <ul>
 * <li>신뢰할 프록시가 보낸 X-Forwarded-For는 접속 IP가 된다: 레이트 리미트가 사용자 IP별로 따로 센다</li>
 * <li>X-Forwarded-Proto: https면 HTTPS로 보고 HSTS를 낸다</li>
 * <li>신뢰하지 않는 곳에서 온 X-Forwarded-For는 무시한다(IP 위조 방지)</li>
 * </ul>
 */
class ForwardedHeadersRealServerTests {

	private static final String PROD_LIKE = "server.forward-headers-strategy=native";

	@Test
	void prodProfileTrustsOnlyTheCaddyAddress() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application-prod.yml"));
		Map<Object, Object> prod = yaml.getObject();

		assertThat(prod).containsEntry("server.forward-headers-strategy", "native")
				.containsEntry("server.tomcat.remoteip.internal-proxies", "172\\.30\\.0\\.10")
				.containsEntry("server.tomcat.remoteip.remote-ip-header", "x-forwarded-for")
				.containsEntry("server.tomcat.remoteip.protocol-header", "x-forwarded-proto");
	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {PROD_LIKE,
			"server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
			"server.tomcat.remoteip.remote-ip-header=x-forwarded-for",
			"server.tomcat.remoteip.protocol-header=x-forwarded-proto"})
	@Import(TestcontainersConfiguration.class)
	class BehindTrustedProxy {

		@LocalServerPort
		int port;

		@Autowired
		JdbcTemplate jdbc;

		@BeforeEach
		void setUp() {
			TestData.reset(jdbc);
		}

		@Test
		void forwardedClientIpIsCountedSeparately() throws Exception {
			send(port, "203.0.113.5", "https");
			send(port, "203.0.113.5", "https");
			send(port, "198.51.100.7", "https");

			assertThat(buckets(jdbc)).containsExactly("tenant-link:ip:198.51.100.7=1", "tenant-link:ip:203.0.113.5=2");
		}

		@Test
		void forwardedHttpsTurnsOnHsts() throws Exception {
			HttpResponse<String> https = send(port, "203.0.113.5", "https");
			HttpResponse<String> http = send(port, "203.0.113.5", "http");

			assertThat(https.headers().firstValue("Strict-Transport-Security")).isPresent();
			assertThat(http.headers().firstValue("Strict-Transport-Security")).isEmpty();
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {PROD_LIKE,
			"server.tomcat.remoteip.internal-proxies=10\\.255\\.255\\.1",
			"server.tomcat.remoteip.remote-ip-header=x-forwarded-for",
			"server.tomcat.remoteip.protocol-header=x-forwarded-proto"})
	@Import(TestcontainersConfiguration.class)
	class FromAnUntrustedAddress {

		@LocalServerPort
		int port;

		@Autowired
		JdbcTemplate jdbc;

		@BeforeEach
		void setUp() {
			TestData.reset(jdbc);
		}

		@Test
		void forgedForwardedHeadersAreIgnored() throws Exception {
			HttpResponse<String> response = send(port, "203.0.113.5", "https");

			assertThat(buckets(jdbc)).containsExactly("tenant-link:ip:127.0.0.1=1");
			assertThat(response.headers().firstValue("Strict-Transport-Security")).as("꾸민 https도 믿지 않는다").isEmpty();
		}

	}

	/** 세입자 링크 주소(/t/**)는 IP당 요청 수를 센다. 틀린 토큰이어도 센다 */
	private static HttpResponse<String> send(int port, String forwardedFor, String proto) throws Exception {
		return HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/t/not-a-token"))
						.header("X-Forwarded-For", forwardedFor).header("X-Forwarded-Proto", proto).build(),
						HttpResponse.BodyHandlers.ofString());
	}

	private static List<String> buckets(JdbcTemplate jdbc) {
		return jdbc.queryForList("select bucket_key || '=' || hit_count from rate_limit_bucket order by bucket_key",
				String.class);
	}

}
