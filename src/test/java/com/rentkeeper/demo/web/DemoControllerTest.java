package com.rentkeeper.demo.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import com.rentkeeper.auth.web.AuthenticatedSessionHelper;
import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.demo.application.DemoService;

/** 데모가 꺼져 있으면(운영 기본값) /demo는 404이고 계정을 만들지 않는다 (M6 결정 3) */
class DemoControllerTest {

	@Test
	void disabledDemoIsNotFound() {
		DemoService demoService = mock(DemoService.class);
		when(demoService.enabled()).thenReturn(false);
		DemoController controller = new DemoController(demoService, mock(AuthenticatedSessionHelper.class),
				mock(RateLimiter.class), new AppProperties(java.net.URI.create("http://localhost:8080"), null),
				Clock.systemUTC());

		assertThatThrownBy(() -> controller.start(null, new ExtendedModelMap(), new MockHttpServletRequest(),
				new MockHttpServletResponse())).isInstanceOf(ResourceNotFoundException.class);
		verify(demoService, never()).start();
	}

}
