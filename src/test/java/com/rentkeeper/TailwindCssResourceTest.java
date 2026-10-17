package com.rentkeeper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tailwind로 빌드한 CSS가 애플리케이션 리소스(classpath:/static/css/app.css)에 포함되는지 확인한다
 * (ADR-007).
 */
class TailwindCssResourceTest {

	@Test
	void builtCssIsOnClasspath() throws IOException {
		try (InputStream css = getClass().getResourceAsStream("/static/css/app.css")) {
			assertThat(css).as("tailwindBuild 결과가 리소스에 없음").isNotNull();
			String content = new String(css.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(content).contains("tailwindcss v4.3.3").contains(".btn-primary");
		}
	}

}
