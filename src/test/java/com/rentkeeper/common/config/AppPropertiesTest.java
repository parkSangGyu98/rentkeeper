package com.rentkeeper.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** 세입자 링크 주소와, 세입자 POST의 Origin과 비교할 출처 (M4 결정 8) */
class AppPropertiesTest {

	@Test
	void tenantLinkUsesTheBaseUrl() {
		assertThat(props("https://rentkeeper.example.com/").tenantLink("abc"))
				.isEqualTo("https://rentkeeper.example.com/t/abc");
	}

	@Test
	void originDropsPathAndDefaultPortLikeBrowsersDo() {
		assertThat(props("http://localhost:8080").origin()).isEqualTo("http://localhost:8080");
		assertThat(props("https://Rentkeeper.Example.com/app/").origin()).isEqualTo("https://rentkeeper.example.com");
		assertThat(props("https://rentkeeper.example.com:443").origin()).isEqualTo("https://rentkeeper.example.com");
		assertThat(props("http://rentkeeper.example.com:80").origin()).isEqualTo("http://rentkeeper.example.com");
		assertThat(props("https://rentkeeper.example.com:8443").origin())
				.isEqualTo("https://rentkeeper.example.com:8443");
	}

	private static AppProperties props(String baseUrl) {
		return new AppProperties(URI.create(baseUrl), null);
	}

}
