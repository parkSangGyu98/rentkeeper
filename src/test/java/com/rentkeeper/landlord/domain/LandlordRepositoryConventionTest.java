package com.rentkeeper.landlord.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 이메일·카카오 회원번호로 계정을 찾는 메서드는 반드시 상태(LandlordStatus) 조건을 받아야 한다 (M1-c 결정 1,
 * ADR-015).
 * <p>
 * 탈퇴 유예 중인 계정과 새로 가입한 계정이 같은 이메일·회원번호를 가질 수 있다. 상태 조건이 빠지면 둘이 함께 조회돼 오류가 나거나,
 * 탈퇴한 계정으로 로그인되는 사고가 난다. 새 조회 메서드를 추가할 때 이 규칙을 빠뜨리면 이 테스트가 실패한다.
 */
class LandlordRepositoryConventionTest {

	@Test
	void lookupsByEmailOrKakaoIdRequireStatus() {
		List<Method> lookups = Arrays.stream(LandlordRepository.class.getDeclaredMethods())
				.filter(m -> m.getName().contains("Email") || m.getName().contains("KakaoId")).toList();

		assertThat(lookups).isNotEmpty();
		assertThat(lookups).allSatisfy(method -> {
			assertThat(method.getName()).as(method.getName()).contains("Status");
			assertThat(method.getParameterTypes()).as(method.getName()).contains(LandlordStatus.class);
		});
	}

}
