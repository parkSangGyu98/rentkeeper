package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 운영 배포 파일끼리 어긋나지 않는지 (M6 커밋 6, ADR-030). 서버에 올리기 전에 빌드에서 잡는다.
 * <ul>
 * <li>앱이 믿는 프록시 주소(application-prod.yml)와 compose가 Caddy에 준 주소가 같다</li>
 * <li>app·postgres는 포트를 밖으로 열지 않는다. Caddy만 80·443</li>
 * <li>Caddy는 /actuator를 막고, 접근 로그에서 세입자 링크 토큰(경로·Referer)을 가린다</li>
 * <li>.env.prod.example은 이름이 겹치지 않고 값이 비어 있으며, 설정이 읽는 환경변수를 모두 담는다</li>
 * </ul>
 */
class DeploymentFilesTest {

	private static final Path ROOT = Path.of(".");

	@Test
	void appTrustsExactlyTheCaddyAddress() throws IOException {
		Map<String, Object> compose = yaml("docker/compose.prod.yml");
		String caddyAddress = (String) map(map(map(map(compose, "services"), "caddy"), "networks"), "internal")
				.get("ipv4_address");
		String trusted = read("src/main/resources/application-prod.yml").lines()
				.filter(line -> line.strip().startsWith("internal-proxies:")).findFirst().orElseThrow().split(":", 2)[1]
				.strip();

		assertThat(trusted.replace("\\.", ".")).isEqualTo(caddyAddress);
	}

	@Test
	void onlyCaddyPublishesPorts() throws IOException {
		Map<String, Object> services = map(yaml("docker/compose.prod.yml"), "services");

		assertThat(map(services, "app")).doesNotContainKey("ports");
		assertThat(map(services, "postgres")).doesNotContainKey("ports");
		assertThat(map(services, "caddy").get("ports")).asList().containsExactlyInAnyOrder("80:80", "443:443",
				"443:443/udp");
	}

	@Test
	void emptyMailPortFallsBackTo587() throws IOException {
		// .env.prod.example은 "비우면 587"이라고 안내한다. 빈 값이 앱에 그대로 가면 Spring 기본값이 쓰이지 않고
		// 25번으로 붙으므로 compose가 빈 값을 587로 바꿔 넣는다 (배포 리허설에서 25번으로 붙는 것을 확인)
		Map<String, Object> app = map(map(yaml("docker/compose.prod.yml"), "services"), "app");

		assertThat(map(app, "environment")).containsEntry("MAIL_PORT", "${MAIL_PORT:-587}");
	}

	@Test
	void caddyHidesActuatorAndMasksTenantTokensInLogs() throws IOException {
		String caddyfile = read("docker/Caddyfile");

		// 접근 로그와 그 밖의 로그(프록시 오류) 모두 가린다: 오류 로그에도 요청 주소·Referer가 남는 것을 로컬 Caddy로 확인했다
		assertThat(caddyfile.split("log default", -1)).hasSize(2);
		assertThat(caddyfile.split(Pattern.quote("request>uri regexp ^/t/"), -1)).as("두 로그 모두").hasSize(3);
		assertThat(caddyfile).contains("@actuator path /actuator /actuator/*").contains("respond @actuator 404")
				.contains("request>uri regexp ^/t/[^/?#]+ /t/***")
				.contains("request>headers>Referer regexp /t/[^/?#]+ /t/***").contains("request>headers>Cookie delete")
				.contains("reverse_proxy app:8080");
	}

	@Test
	void prodEnvExampleListsEveryVariableOnceWithoutValues() throws IOException {
		List<String> names = new ArrayList<>();
		for (String line : read(".env.prod.example").lines().toList()) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] parts = line.split("=", 2);
			names.add(parts[0]);
			if (!parts[0].equals("APP_NOTIFICATION_TENANT_PHONE_ENABLED")) {
				assertThat(parts[1]).as(parts[0] + "는 값이 비어 있어야 한다").isEmpty();
			}
		}
		assertThat(names).doesNotHaveDuplicates();

		Set<String> referenced = new TreeSet<>();
		Matcher placeholder = Pattern.compile("(?<!\\$)\\$\\{([A-Z_]+)")
				.matcher(read("src/main/resources/application.yml") + read("src/main/resources/application-prod.yml")
						+ read("docker/compose.prod.yml"));
		while (placeholder.find()) {
			referenced.add(placeholder.group(1));
		}
		// AppProperties가 app.base-url을 환경변수 APP_BASE_URL에서 직접 읽는다(placeholder 없음)
		referenced.add("APP_BASE_URL");
		// compose가 만들어 넣는다
		referenced.remove("DB_URL");
		assertThat(new LinkedHashSet<>(names)).containsAll(referenced);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Map<String, Object> parent, String key) {
		return (Map<String, Object>) parent.get(key);
	}

	private static Map<String, Object> yaml(String path) throws IOException {
		return new Yaml().load(read(path));
	}

	private static String read(String path) throws IOException {
		return Files.readString(ROOT.resolve(path), StandardCharsets.UTF_8);
	}

}
