package com.rentkeeper.common.jpa;

import org.hibernate.exception.ConstraintViolationException;

/**
 * DB 제약 위반을 제약 이름으로 알아본다. 중복 이름 같은 위반을 친절한 도메인 예외로 바꿀 때 쓴다(M1 중복 가입과 같은 방식). DB
 * 예외 메시지는 화면에 내보내지 않는다.
 */
public final class Constraints {

	private Constraints() {
	}

	public static boolean isViolated(Throwable e, String constraintName) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof ConstraintViolationException cve
					&& constraintName.equalsIgnoreCase(cve.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

}
