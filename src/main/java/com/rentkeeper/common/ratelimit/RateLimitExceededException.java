package com.rentkeeper.common.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 요청 한도 초과. 컨트롤러에서 던지면 429 상태와 templates/error/429.html 안내 화면이 나간다.
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {

	public RateLimitExceededException(RateLimitRule rule) {
		super("rate limit exceeded: " + rule);
	}

}
