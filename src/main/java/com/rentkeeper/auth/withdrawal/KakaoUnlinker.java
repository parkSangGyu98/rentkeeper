package com.rentkeeper.auth.withdrawal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.auth.kakao.KakaoUnlinkClient;
import com.rentkeeper.auth.kakao.KakaoUnlinkResult;
import com.rentkeeper.common.ops.OpsAlertNotifier;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 카카오 연결 끊기를 한 번 시도하고 결과를 기록한다 (ADR-015). 탈퇴 직후와 파기 배치의 재시도가 같은 경로를 쓴다.
 * <ul>
 * <li>같은 회원번호의 사용 중(ACTIVE) 계정이 있으면 끊지 않는다: 다시 동의해 쓰는 연결이다. 대기 행만 지운다</li>
 * <li>성공: 같은 회원번호의 탈퇴 계정 행 모두에 kakao_unlinked_at을 채우고 대기 행을 지운다</li>
 * <li>실패: 대기 행에 실패를 기록한다 (없으면 만든다)</li>
 * </ul>
 * 카카오 호출은 트랜잭션 밖에서 한다(응답을 기다리는 동안 DB 연결을 잡지 않게). 기록은 항상 새 트랜잭션으로 한다: 탈퇴 트랜잭션의
 * 커밋 직후(afterCommit)에 불리면 기존 트랜잭션에 참여한 변경은 커밋되지 않기 때문이다.
 */
@Component
public class KakaoUnlinker {

	/** 연결 끊기 운영자 알림의 정해진 문구 (로그 검색용) */
	public static final String ALERT_CODE = "KAKAO_UNLINK_ALERT";

	private static final Logger log = LoggerFactory.getLogger(KakaoUnlinker.class);

	private final KakaoUnlinkClient unlinkClient;

	private final LandlordService landlordService;

	private final KakaoUnlinkPendingStore pendingStore;

	private final OpsAlertNotifier opsAlerts;

	private final Clock clock;

	private final TransactionTemplate newTransaction;

	KakaoUnlinker(KakaoUnlinkClient unlinkClient, LandlordService landlordService, KakaoUnlinkPendingStore pendingStore,
			OpsAlertNotifier opsAlerts, Clock clock, PlatformTransactionManager transactionManager) {
		this.unlinkClient = unlinkClient;
		this.landlordService = landlordService;
		this.pendingStore = pendingStore;
		this.opsAlerts = opsAlerts;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @param withdrawnAt
	 *            대기 행에 남길 탈퇴 시각 (실패할 때)
	 * @return 호출 결과. 사용 중인 계정이 있어 끊지 않았으면 빈 값
	 */
	public Optional<KakaoUnlinkResult> attempt(String kakaoId, Instant withdrawnAt) {
		if (landlordService.hasActiveKakaoAccount(kakaoId)) {
			newTransaction.executeWithoutResult(status -> pendingStore.delete(kakaoId));
			return Optional.empty();
		}
		KakaoUnlinkResult result = unlinkClient.unlink(kakaoId);
		newTransaction.executeWithoutResult(status -> {
			if (result.isFailure()) {
				pendingStore.recordFailure(kakaoId, withdrawnAt, result, clock.instant());
			} else {
				landlordService.markKakaoUnlinked(kakaoId);
				pendingStore.delete(kakaoId);
			}
		});
		return Optional.of(result);
	}

	/** 탈퇴 직후 한 번 시도한다. 실패해도 탈퇴는 그대로이고, 대기 행이 남아 파기 배치가 다시 시도한다 */
	void attemptAfterWithdrawal(long landlordId, String kakaoId, Instant withdrawnAt) {
		Optional<KakaoUnlinkResult> result = attempt(kakaoId, withdrawnAt);
		if (result.isPresent() && result.get().isFailure()) {
			// 회원번호는 개인정보라 로그에 남기지 않는다
			log.warn("kakao unlink after withdrawal failed, will retry in purge: landlordId={}, type={}", landlordId,
					result.get());
			if (result.get() == KakaoUnlinkResult.CONFIG) {
				// 설정 오류는 첫 실패에 바로 알린다 (배치 시각을 기다리지 않음)
				opsAlerts.alert(ALERT_CODE, "source=withdrawal config=1");
			}
		}
	}

}
