package com.rentkeeper.auth.signup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;

/**
 * 초대 코드 가입 제한 (M6 결정 3). 공개 주소에서 낯선 사람이 가입해 실제 개인정보를 넣지 않게, 일반 가입(이메일·카카오)은 초대
 * 코드가 있어야 한다. 방문자는 임시 데모 계정을 쓴다.
 * <p>
 * 설정(app.signup.invite-code, 환경변수 APP_SIGNUP_INVITE_CODE)이 비어 있으면 제한이
 * 없다(local·테스트). 운영은 반드시 넣는다({@link ProdInviteCodeGuard}).
 * <ul>
 * <li>입력 시도는 IP당 1시간 10회까지 센다(맞든 틀리든). 넘으면 429</li>
 * <li>비교는 두 값의 SHA-256을 {@link MessageDigest#isEqual}로 한다: 걸린 시간으로 맞은 글자 수나 길이를
 * 알 수 없다</li>
 * <li>코드 원문은 메모리에 두지 않고 해시만 둔다. 로그에 남기지 않는다</li>
 * </ul>
 */
@Component
public class InviteCodeGate {

	private final byte[] expectedHash;

	private final RateLimiter rateLimiter;

	public InviteCodeGate(@Value("${app.signup.invite-code:}") String inviteCode, RateLimiter rateLimiter) {
		this.expectedHash = inviteCode == null || inviteCode.isBlank() ? null : sha256(inviteCode.strip());
		this.rateLimiter = rateLimiter;
	}

	/** 가입할 때 초대 코드를 받아야 하는지 */
	public boolean required() {
		return expectedHash != null;
	}

	/**
	 * 입력한 초대 코드가 맞는지. 제한이 없으면 늘 true.
	 *
	 * @throws com.rentkeeper.common.ratelimit.RateLimitExceededException
	 *             IP당 시도 한도를 넘었을 때
	 */
	public boolean accepts(String submitted, String clientIp) {
		if (!required()) {
			return true;
		}
		rateLimiter.acquireOrThrow(RateLimitRule.INVITE_CODE_PER_IP, clientIp);
		return MessageDigest.isEqual(expectedHash, sha256(submitted == null ? "" : submitted.strip()));
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
