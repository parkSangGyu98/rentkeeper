package com.rentkeeper.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 템플릿에서 보안 규칙을 어기는 코드를 빌드 단계에서 막는다 (PRD 8.1, ADR-008).
 * <ul>
 * <li>{@code th:utext}: HTML 이스케이프를 끄므로 XSS 위험 → 금지</li>
 * <li>인라인 {@code <script>}, {@code style=""}, {@code onclick=""} 등:
 * CSP(script-src/style-src 'self')에 막힌다 → 금지</li>
 * </ul>
 */
class TemplateGuardTest {

	private static final Path TEMPLATES = Path.of("src/main/resources/templates");

	private static final List<Rule> RULES = List.of(new Rule("th:utext 금지(XSS)", Pattern.compile("th:utext")),
			new Rule("인라인 <script> 금지(CSP)",
					Pattern.compile("<script(?![^>]*\\ssrc\\s*=)[^>]*>", Pattern.CASE_INSENSITIVE)),
			new Rule("style 속성 금지(CSP)", Pattern.compile("\\s(th:)?style\\s*=", Pattern.CASE_INSENSITIVE)),
			new Rule("<style> 태그 금지(CSP)", Pattern.compile("<style[\\s>]", Pattern.CASE_INSENSITIVE)),
			new Rule("인라인 이벤트 속성 금지(CSP)", Pattern.compile("\\s(th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE)),
			new Rule("javascript: 링크 금지(CSP)", Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE)));

	@Test
	void templatesFollowSecurityRules() throws IOException {
		List<Path> templates;
		try (Stream<Path> files = Files.walk(TEMPLATES)) {
			templates = files.filter(p -> p.toString().endsWith(".html")).toList();
		}
		assertThat(templates).as("템플릿을 찾지 못함").isNotEmpty();

		List<String> violations = new ArrayList<>();
		for (Path template : templates) {
			String html = Files.readString(template, StandardCharsets.UTF_8);
			for (Rule rule : RULES) {
				Matcher matcher = rule.pattern().matcher(html);
				if (matcher.find()) {
					violations.add(template + ": " + rule.name() + " → " + matcher.group());
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void guardDetectsViolations() {
		// 규칙 자체가 동작하는지 확인
		assertThat(matches("<p th:utext=\"${x}\">")).contains("th:utext 금지(XSS)");
		assertThat(matches("<script>alert(1)</script>")).contains("인라인 <script> 금지(CSP)");
		assertThat(matches("<script src=\"/js/app.js\"></script>")).isEmpty();
		assertThat(matches("<div style=\"color:red\">")).contains("style 속성 금지(CSP)");
		assertThat(matches("<button onclick=\"go()\">")).contains("인라인 이벤트 속성 금지(CSP)");
		assertThat(matches("<a href=\"javascript:go()\">")).contains("javascript: 링크 금지(CSP)");
	}

	private static List<String> matches(String html) {
		return RULES.stream().filter(rule -> rule.pattern().matcher(html).find()).map(Rule::name).toList();
	}

	private record Rule(String name, Pattern pattern) {
	}

}
