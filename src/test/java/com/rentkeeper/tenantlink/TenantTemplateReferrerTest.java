package com.rentkeeper.tenantlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 세입자 링크 화면(templates/tenant)의 referrer 설정 (브라우저 확인 F1, ADR-026). 응답 헤더는
 * same-origin이고, 템플릿이 이를 덮어쓰면 안 된다.
 * <ul>
 * <li>no-referrer를 쓰면 브라우저가 폼 제출의 Origin 헤더를 null로 보내 "입금했어요"가 Origin 검사(M4 결정
 * 8)에 막힌다</li>
 * <li>폼·링크의 referrerpolicy 속성, rel="noreferrer"도 같은 효과라 쓰지 않는다</li>
 * <li>외부 주소(http:, https:, //)는 쓰지 않는다 (렌더된 화면은 TenantPageTests가 따로 검사)</li>
 * </ul>
 * MockMvc는 브라우저의 Origin 동작을 재현하지 못하므로, 실제 Chrome에서 폼을 제출하는 확인(progress 47번)이 최종
 * 근거다.
 */
class TenantTemplateReferrerTest {

	private static final Path TENANT_TEMPLATES = Path.of("src/main/resources/templates/tenant");

	private static final Pattern META_REFERRER = Pattern.compile("<meta\\s+name=\"referrer\"\\s+content=\"([^\"]*)\"");

	private static final List<Pattern> FORBIDDEN = List.of(Pattern.compile("no-referrer", Pattern.CASE_INSENSITIVE),
			Pattern.compile("referrerpolicy\\s*=", Pattern.CASE_INSENSITIVE),
			Pattern.compile("rel\\s*=\\s*\"[^\"]*noreferrer", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(src|href|action)\\s*=\\s*\"\\s*(https?:)?//", Pattern.CASE_INSENSITIVE));

	@Test
	void layoutDeclaresSameOriginAndNoTemplateOverridesIt() throws IOException {
		List<Path> templates;
		try (Stream<Path> files = Files.list(TENANT_TEMPLATES)) {
			templates = files.filter(p -> p.toString().endsWith(".html")).toList();
		}
		assertThat(templates).as("세입자 템플릿을 찾지 못함").isNotEmpty();

		List<String> metas = new ArrayList<>();
		List<String> violations = new ArrayList<>();
		for (Path template : templates) {
			String html = Files.readString(template, StandardCharsets.UTF_8);
			META_REFERRER.matcher(html).results().forEach(m -> metas.add(template.getFileName() + "=" + m.group(1)));
			for (Pattern pattern : FORBIDDEN) {
				pattern.matcher(html).results().forEach(m -> violations.add(template.getFileName() + ": " + m.group()));
			}
		}

		assertThat(metas).as("referrer meta는 레이아웃에 하나, same-origin").containsExactly("layout.html=same-origin");
		assertThat(violations).isEmpty();
	}

}
