package com.rentkeeper.auth.withdrawal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.auth.kakao.KakaoUnlinkClient;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 탈퇴한 카카오 계정의 연결을 끊고, 성공하면 시각(kakao_unlinked_at)을 남긴다. 실패하면 NULL로 남아 파기 배치가 다시
 * 시도한다 (ADR-015).
 * <p>
 * 카카오 호출은 트랜잭션 밖에서 한다 (응답을 기다리는 동안 DB 연결을 잡지 않게). 기록은 항상 새 트랜잭션으로 한다: 탈퇴 트랜잭션의
 * 커밋 직후(afterCommit)에 불리면 기존 트랜잭션에 참여한 변경은 커밋되지 않기 때문이다.
 */
@Component
public class KakaoUnlinker {

	private static final Logger log = LoggerFactory.getLogger(KakaoUnlinker.class);

	private final KakaoUnlinkClient unlinkClient;

	private final LandlordService landlordService;

	private final TransactionTemplate newTransaction;

	public KakaoUnlinker(KakaoUnlinkClient unlinkClient, LandlordService landlordService,
			PlatformTransactionManager transactionManager) {
		this.unlinkClient = unlinkClient;
		this.landlordService = landlordService;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** @return 끊기에 성공했으면 true */
	public boolean unlink(long landlordId, String kakaoId) {
		if (!unlinkClient.unlink(kakaoId)) {
			// 회원번호는 개인정보라 로그에 남기지 않는다
			log.warn("kakao unlink not done, will retry in purge: landlordId={}", landlordId);
			return false;
		}
		newTransaction.executeWithoutResult(status -> landlordService.markKakaoUnlinked(landlordId));
		return true;
	}

}
