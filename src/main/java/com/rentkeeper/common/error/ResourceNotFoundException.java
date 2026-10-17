package com.rentkeeper.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 없거나 다른 임대인의 리소스 (ADR-018). 둘을 구분하지 않고 같은 404를 돌려준다: 남의 ID를 넣어 존재 여부를 알아내지 못하게
 * (PRD 7.4). 메시지는 내부 로그용이고 화면에는 나오지 않는다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String resource) {
		super(resource + " not found");
	}

}
