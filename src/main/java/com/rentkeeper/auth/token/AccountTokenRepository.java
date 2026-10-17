package com.rentkeeper.auth.token;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

	Optional<AccountToken> findByTokenHash(String tokenHash);

	/** 같은 계정·용도의 아직 쓸 수 있는 토큰을 모두 무효로 만든다 (새 링크를 보내면 이전 링크는 쓸 수 없다) */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update AccountToken t set t.usedAt = :now, t.updatedAt = :now
			where t.landlordId = :landlordId and t.purpose = :purpose and t.usedAt is null
			""")
	int invalidateUsable(@Param("landlordId") long landlordId, @Param("purpose") TokenPurpose purpose,
			@Param("now") Instant now);

	/**
	 * 토큰을 사용 처리한다. 조건(미사용·미만료)과 변경을 한 문장으로 처리하므로 같은 링크를 동시에 두 번 눌러도 1건만 성공한다.
	 *
	 * @return 1이면 이번 요청이 사용에 성공, 0이면 이미 쓰였거나 만료
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update AccountToken t set t.usedAt = :now, t.updatedAt = :now
			where t.id = :id and t.usedAt is null and t.expiresAt > :now
			""")
	int consume(@Param("id") long id, @Param("now") Instant now);

	@Modifying
	@Query("delete from AccountToken t where t.expiresAt < :cutoff")
	int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

}
