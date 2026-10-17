package com.rentkeeper.landlord.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 이메일·카카오 회원번호로 찾는 메서드는 <strong>반드시 상태(status) 조건</strong>을 받는다. 탈퇴 유예 중인 계정과
 * 새로 가입한 계정이 같은 이메일·회원번호를 가질 수 있기 때문이다 (ADR-015).
 * LandlordRepositoryConventionTest가 이 규칙을 검사한다.
 */
public interface LandlordRepository extends JpaRepository<Landlord, Long> {

	boolean existsByAuthProviderAndEmailAndStatus(AuthProvider authProvider, String email, LandlordStatus status);

	Optional<Landlord> findByAuthProviderAndEmailAndStatus(AuthProvider authProvider, String email,
			LandlordStatus status);

	Optional<Landlord> findByAuthProviderAndKakaoIdAndStatus(AuthProvider authProvider, String kakaoId,
			LandlordStatus status);

	/** 탈퇴 계정은 같은 회원번호로 여러 행일 수 있다 (탈퇴-재가입-탈퇴) */
	List<Landlord> findAllByAuthProviderAndKakaoIdAndStatus(AuthProvider authProvider, String kakaoId,
			LandlordStatus status);

}
