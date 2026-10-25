package com.rentkeeper.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.TestData;

/**
 * 서비스명은 messages의 app.name 한 곳에서 읽는다 (디자인 교체 결정 3). 그래도 화면에 보이는 글자는 바뀌기 전과 같아야
 * 한다.
 * <ul>
 * <li>템플릿 제목: 바꾸기 전 제목을 그대로 떠 둔 목록(title-snapshot.txt)과, 지금 템플릿의 제목식에 app.name을
 * 넣은 결과가 같다</li>
 * <li>실제로 그린 화면: 첫 화면·로그인·가입·약관·대시보드의 &lt;title&gt;과 머리글 이름이 바꾸기 전과 같다</li>
 * <li>템플릿에 서비스명을 직접 쓰지 않는다(바꿀 때 고칠 곳이 다시 흩어지지 않게)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ServiceNameTests {

	private static final Path TEMPLATES = Path.of("src/main/resources/templates");

	private static final Pattern TITLE = Pattern.compile("<title th:text=\"\\|([^|\"]*)\\|\">[^<]*</title>");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
	}

	@Test
	void everyTemplateTitleRendersAsBefore() throws IOException {
		String name = appName();
		List<String> mismatches = new ArrayList<>();
		List<String> lines = Files.readAllLines(Path.of("src/test/resources/design/title-snapshot.txt"),
				StandardCharsets.UTF_8);
		int checked = 0;
		for (String line : lines) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] parts = line.split("\\|", 2);
			Matcher title = TITLE.matcher(Files.readString(TEMPLATES.resolve(parts[0]), StandardCharsets.UTF_8));
			String rendered = title.find() ? title.group(1).replace("#{app.name}", name) : "(제목식 없음)";
			if (!rendered.equals(parts[1])) {
				mismatches.add(parts[0] + ": " + rendered + " (전: " + parts[1] + ")");
			}
			checked++;
		}

		assertThat(checked).isEqualTo(54);
		assertThat(mismatches).isEmpty();
	}

	@Test
	void renderedTitlesAndHeaderAreUnchanged() throws Exception {
		Map<String, String> pages = Map.of("/", "RentKeeper - 월세 관리를 쉽게", "/login", "로그인 - RentKeeper", "/signup",
				"가입하기 - RentKeeper", "/terms", "이용약관 - RentKeeper", "/privacy", "개인정보처리방침 - RentKeeper");
		for (Map.Entry<String, String> page : pages.entrySet()) {
			String html = mockMvc.perform(get(page.getKey())).andReturn().getResponse().getContentAsString();
			assertThat(html).as(page.getKey()).contains("<title>" + page.getValue() + "</title>")
					.contains("no-underline\">RentKeeper</a>");
		}

		Session a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		String dashboard = mockMvc.perform(get("/app/dashboard").cookie(a.cookie())).andReturn().getResponse()
				.getContentAsString();
		assertThat(dashboard).contains("<title>한눈에 보기 - RentKeeper</title>").contains("no-underline\">RentKeeper</a>");
	}

	@Test
	void templatesDoNotSpellTheServiceName() throws IOException {
		String name = appName();
		List<Path> spelled = new ArrayList<>();
		try (Stream<Path> files = Files.walk(TEMPLATES)) {
			for (Path template : files.filter(p -> p.toString().endsWith(".html")).toList()) {
				if (Files.readString(template, StandardCharsets.UTF_8).contains(name)) {
					spelled.add(template);
				}
			}
		}
		assertThat(spelled).as("서비스명은 #{app.name}으로 쓴다").isEmpty();
	}

	private static String appName() throws IOException {
		Properties messages = new Properties();
		try (var reader = Files.newBufferedReader(Path.of("src/main/resources/messages.properties"),
				StandardCharsets.UTF_8)) {
			messages.load(reader);
		}
		return messages.getProperty("app.name");
	}

}
