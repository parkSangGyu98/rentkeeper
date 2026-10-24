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

import org.junit.jupiter.api.Test;

/**
 * 임대인 머리글이 좁은 화면(500px 안팎)에서 글자를 꺾지 않는다 (브라우저 확인 F2). 줄이 모자라면 항목째 다음 줄로 넘긴다. 실제
 * 모양은 브라우저에서 확인한다(progress 53번 재확인); 이 테스트는 그 규칙이 지워지지 않게 막는다.
 */
class HeaderLayoutTest {

	private static final Path LAYOUT = Path.of("src/main/resources/templates/fragments/layout.html");

	@Test
	void headerWrapsByItemAndNeverBreaksInsideAnItem() throws IOException {
		String html = Files.readString(LAYOUT, StandardCharsets.UTF_8);
		String header = html.substring(html.indexOf("<header"), html.indexOf("</header>"));

		assertThat(header).contains("<nav class=\"flex flex-wrap").contains("flex-wrap items-center justify-between");
		List<String> breakable = new ArrayList<>();
		Matcher items = Pattern.compile("<(a|button)\\s[^>]*>").matcher(header);
		while (items.find()) {
			if (!items.group().contains("whitespace-nowrap")) {
				breakable.add(items.group());
			}
		}
		assertThat(breakable).as("머리글의 링크·버튼은 모두 whitespace-nowrap").isEmpty();
	}

	/**
	 * 임대인 메뉴는 넓은 화면(lg)에서 한 줄, 좁은 화면에서 [메뉴]로 접는다 (디자인 교체 결정 4). 두 메뉴가 함께 있으므로 지금 폭에
	 * 맞지 않는 쪽은 display:none(Tailwind hidden)이어야 화면 읽기 프로그램에 두 번 읽히지 않는다. 두 메뉴의 링크는
	 * 같다.
	 */
	@Test
	void wideAndNarrowMenusNeverShowTogetherAndHaveTheSameLinks() throws IOException {
		String html = Files.readString(LAYOUT, StandardCharsets.UTF_8);
		String header = html.substring(html.indexOf("<header"), html.indexOf("</header>"));
		String wide = header.substring(header.indexOf("<nav id=\"menu-wide\""), header.indexOf("<details"));
		String narrow = header.substring(header.indexOf("<details id=\"menu-narrow\""), header.indexOf("</details>"));

		assertThat(wide).startsWith("<nav id=\"menu-wide\" class=\"hidden lg:flex").contains("aria-label=\"메뉴\"");
		assertThat(narrow).contains("lg:hidden").contains("<summary").contains("aria-label=\"메뉴\"");
		assertThat(links(narrow)).isEqualTo(links(wide).subList(1, links(wide).size()))
				.containsExactly("@{/app/dashboard}", "@{/app/charges}", "@{/app/properties}", "@{/app/settings}");
		assertThat(wide).contains("@{/logout}");
		assertThat(narrow).contains("@{/logout}");
	}

	private static List<String> links(String html) {
		List<String> links = new ArrayList<>();
		Matcher href = Pattern.compile("<a th:href=\"([^\"]+)\"").matcher(html);
		while (href.find()) {
			links.add(href.group(1));
		}
		return links;
	}

}
