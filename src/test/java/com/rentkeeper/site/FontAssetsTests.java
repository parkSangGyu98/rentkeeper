package com.rentkeeper.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.rentkeeper.TestcontainersConfiguration;

/**
 * 서버에 포함한 한글 글꼴 Pretendard (디자인 교체 커밋 2, 결정 2).
 * <ul>
 * <li>CSS의 url(…)은 모두 같은 서버의 상대 경로다: http·https·//로 시작하는 외부 주소가 없다(빌드한 app.css
 * 포함). CSP font-src 'self'와 세입자 화면 외부 요청 금지(TenantTemplateReferrerTest,
 * TenantPageTests)를 지킨다</li>
 * <li>글꼴 CSS와 조각 파일이 /css/fonts/… 에서 200으로 온다(로그인 없이, 세입자 화면도 쓴다)</li>
 * <li>CSS가 가리키는 조각 파일이 모두 있고, 라이선스 원문이 함께 있고, 파일이 받은 그대로다(SHA256SUMS)</li>
 * <li>임대인·세입자 레이아웃이 모두 글꼴 CSS를 연결한다</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FontAssetsTests {

	private static final Path FONTS = Path.of("src/main/resources/static/css/fonts/pretendard");

	private static final String FONT_CSS = "/css/fonts/pretendard/pretendardvariable-dynamic-subset.css";

	private static final Pattern URL = Pattern.compile("url\\(\\s*['\"]?([^)'\"]+)");

	@Autowired
	MockMvc mockMvc;

	@Test
	void fontCssAndFilesAreServedFromThisServer() throws Exception {
		MvcResult css = mockMvc.perform(get(FONT_CSS)).andReturn();
		assertThat(css.getResponse().getStatus()).isEqualTo(200);
		assertThat(css.getResponse().getContentType()).startsWith("text/css");

		List<String> urls = urls(css.getResponse().getContentAsString());
		assertThat(urls).hasSize(92);
		for (String url : List.of(urls.get(0), urls.get(urls.size() - 1))) {
			MvcResult font = mockMvc.perform(get("/css/fonts/pretendard/" + url.substring(2))).andReturn();
			assertThat(font.getResponse().getStatus()).as(url).isEqualTo(200);
			assertThat(font.getResponse().getContentAsByteArray()).hasSizeGreaterThan(1000);
		}
	}

	@Test
	void noCssPointsOutsideThisServer() throws IOException {
		String built;
		try (InputStream in = getClass().getResourceAsStream("/static/css/app.css")) {
			built = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		String fonts = Files.readString(FONTS.resolve("pretendardvariable-dynamic-subset.css"), StandardCharsets.UTF_8);

		for (String url : urls(built + fonts)) {
			assertThat(url).as("외부 주소").doesNotStartWith("http").doesNotStartWith("//");
		}
		assertThat(urls(fonts)).allSatisfy(url -> assertThat(url).startsWith("./woff2-dynamic-subset/"));
	}

	@Test
	void everyReferencedFileExistsWithTheLicenseAndMatchesItsChecksum() throws Exception {
		String fonts = Files.readString(FONTS.resolve("pretendardvariable-dynamic-subset.css"), StandardCharsets.UTF_8);
		for (String url : urls(fonts)) {
			assertThat(FONTS.resolve(url.substring(2))).as(url).exists();
		}
		assertThat(Files.readString(FONTS.resolve("LICENSE.txt"), StandardCharsets.UTF_8))
				.contains("SIL Open Font License, Version 1.1").contains("Reserved Font Name Pretendard");

		List<String> mismatches = new ArrayList<>();
		int checked = 0;
		for (String line : Files.readAllLines(FONTS.resolve("SHA256SUMS"), StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] parts = line.split("\\s+", 2);
			if (!sha256(FONTS.resolve(parts[1].strip())).equals(parts[0])) {
				mismatches.add(parts[1]);
			}
			checked++;
		}
		assertThat(checked).isEqualTo(94);
		assertThat(mismatches).isEmpty();
	}

	@Test
	void bothLayoutsLinkTheFontCss() throws IOException {
		for (String layout : List.of("fragments/layout.html", "tenant/layout.html")) {
			assertThat(Files.readString(Path.of("src/main/resources/templates", layout), StandardCharsets.UTF_8))
					.as(layout).contains("th:href=\"@{" + FONT_CSS + "}\"");
		}
	}

	private static List<String> urls(String css) {
		List<String> urls = new ArrayList<>();
		Matcher matcher = URL.matcher(css);
		while (matcher.find()) {
			if (!matcher.group(1).startsWith("data:")) {
				urls.add(matcher.group(1).strip());
			}
		}
		return urls;
	}

	private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}

}
