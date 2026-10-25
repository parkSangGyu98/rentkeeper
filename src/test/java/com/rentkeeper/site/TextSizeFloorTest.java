package com.rentkeeper.site;

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
 * 글자는 16px 아래로 쓰지 않는다 (디자인 교체 결정 9, 40~60대 가독성). 루트가 18px이라 Tailwind의
 * text-sm(0.875rem ≈ 15.75px)·text-xs(0.75rem = 13.5px)가 16px보다 작다. 템플릿과 공통
 * 스타일에서 이 둘을 쓰지 않는다. 상태 아이콘은 글자와 함께 쓰므로 화면 읽기 프로그램에 숨긴다(aria-hidden).
 */
class TextSizeFloorTest {

	private static final Pattern SMALL_TEXT = Pattern.compile("\\btext-(xs|sm)\\b");

	@Test
	void noTextSmallerThanSixteenPixels() throws IOException {
		List<String> found = new ArrayList<>();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(Path.of("src/main/resources/templates"))) {
			files = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".html")).toList());
		}
		files.add(Path.of("src/main/tailwind/app.css"));
		for (Path file : files) {
			if (SMALL_TEXT.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
				found.add(file.toString());
			}
		}
		assertThat(found).isEmpty();
	}

	@Test
	void statusIconsAreHiddenFromScreenReaders() throws IOException {
		String icons = Files.readString(Path.of("src/main/resources/templates/fragments/icons.html"),
				StandardCharsets.UTF_8);
		long svgs = icons.split("<svg", -1).length - 1;

		assertThat(svgs).isEqualTo(6);
		assertThat(icons.split("aria-hidden=\"true\"", -1).length - 1).isEqualTo(svgs);
	}

}
