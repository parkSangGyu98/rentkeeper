package com.rentkeeper.landlord.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/** 아직 지워지지 않은 데모 계정 수 (동시 상한, M6 결정 3) */
	long countByDemoExpiresAtAfter(Instant now);

	/** 지울 때가 된 데모 계정 */
	@Query("select l.id from Landlord l where l.demoExpiresAt <= :now order by l.id")
	List<Long> findExpiredDemoIds(@Param("now") Instant now);

	/**
	 * 데모 계정 만들기를 한 줄로 세운다(트랜잭션이 끝나면 풀린다). 동시에 여러 명이 눌러도 상한을 넘지 않게, 세고 만드는 사이에 끼어들지
	 * 못하게 한다
	 */
	@Query(value = "select 1 from (select pg_advisory_xact_lock(:key)) l", nativeQuery = true)
	int lockDemoCreation(@Param("key") long key);

}
