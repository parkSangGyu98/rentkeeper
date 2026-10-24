package com.rentkeeper.notification.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.rentkeeper.common.money.Won;

/**
 * 알림 문구 (docs/notification-templates.md와 같게 유지한다). 40~60대가 읽는다고 보고 쉬운 말로 쓴다.
 * "연체·미납·독촉"은 쓰지 않는다(테스트로 확인). 세입자 문구에는 계좌번호·전화번호·이름을 넣지 않는다: 계좌는 링크 화면에서 본다.
 * <p>
 * 수신 거부 끝줄과 문자 발송의 법적 요건은 배포 전 전문가 검토 항목이다(progress.md).
 */
public final class NotificationTemplates {

	/** 세입자 문자 끝줄 (전문가 검토 대상) */
	public static final String TENANT_FOOTER = "안내 문자를 그만 받으시려면 링크 화면 아래 [안내 문자 그만 받기]를 눌러 주세요.";

	/** 세입자 문자의 제목 (문자 대행사가 제목을 쓰는 경우) */
	public static final String TENANT_SUBJECT = "월세 안내";

	private NotificationTemplates() {
	}

	/** 받을 월세 안내 */
	public static String chargeIssued(String unitName, YearMonth month, long amount, LocalDate dueDate, String link) {
		return tenant(unitName, "%d월 월세 안내드립니다. 보내실 금액 %s, 보내실 날짜 %s까지입니다. 자세히 보기: %s".formatted(month.getMonthValue(),
				Won.format(amount), day(dueDate), link));
	}

	/** 납부일 전날 안내 */
	public static String dueReminder(String unitName, YearMonth month, LocalDate dueDate, String link) {
		return tenant(unitName, "내일(%s)은 %d월 월세를 보내시는 날입니다. 이미 보내셨다면 이 안내는 넘기셔도 됩니다. %s".formatted(day(dueDate),
				month.getMonthValue(), link));
	}

	/** 납부일 지남 안내 (D+1) */
	public static String overdueFirst(String unitName, YearMonth month, String link) {
		return tenant(unitName, "%d월 월세 입금이 아직 확인되지 않았습니다. 이미 보내셨다면 링크에서 [입금했어요]를 눌러 알려 주세요. %s"
				.formatted(month.getMonthValue(), link));
	}

	/** 납부일 지남 안내 (D+7) */
	public static String overdueSecond(String unitName, YearMonth month, long balance, String link) {
		return tenant(unitName, "%d월 월세 %s의 입금 확인을 기다리고 있습니다. 사정이 있으시면 집주인에게 편하게 연락 주세요. 이미 보내셨다면 링크에서 알려 주세요. %s"
				.formatted(month.getMonthValue(), Won.format(balance), link));
	}

	/** 임대인 메일 (제목, 본문) */
	public record Mail(String subject, String body) {

		@Override
		public String toString() {
			return "Mail[***]";
		}

	}

	/** 입금 신고 접수 (임대인, 시간당 1통) */
	public static Mail tenantReports(int count, String dashboardLink) {
		return new Mail("세입자가 입금했다고 알린 받을 월세가 %d건 있어요".formatted(count),
				"통장을 확인하신 뒤 [입금 확인하기] 또는 [입금이 없어요]를 눌러 주세요.\n바로 가기: " + dashboardLink);
	}

	/** 납부일 지남 요약 (임대인, 하루 1통) */
	public static Mail overdueDigest(int count, String chargesLink) {
		return new Mail("납부일이 지난 받을 월세 %d건".formatted(count),
				"어제가 납부일이었던 받을 월세 중 아직 다 받지 않은 것이 %d건 있어요.\n바로 가기: %s".formatted(count, chargesLink));
	}

	/**
	 * 곧 끝나는 계약 (임대인, 하루 1통). 세입자 이름·연락처는 넣지 않는다.
	 *
	 * @param lines
	 *            "건물 호실 — 끝나는 날 (n일 뒤)" 줄들
	 */
	public static Mail leaseEndDigest(List<String> lines, String dashboardLink) {
		return new Mail("곧 끝나는 계약이 %d건 있어요".formatted(lines.size()),
				String.join("\n", lines) + "\n\n다시 계약할지, 새 세입자를 구할지 미리 정해 두세요.\n바로 가기: " + dashboardLink);
	}

	/** 운영 알림 (코드별 하루 1통). 건수·종류는 서버 로그에서 본다 */
	public static Mail opsAlert(String code) {
		return new Mail("[운영] " + code + " 발생",
				"오늘 처음 발생했어요. 건수와 종류는 서버 로그(rentkeeper.ops-alert)를 확인하세요. 같은 코드는 오늘 다시 보내지 않아요.");
	}

	/** 곧 끝나는 계약 한 줄 */
	public static String leaseEndLine(String unitName, LocalDate endDate, long daysLeft) {
		return "%s — %s (%d일 뒤)".formatted(unitName, day(endDate), daysLeft);
	}

	private static String tenant(String unitName, String sentence) {
		return "[" + unitName + "] " + sentence + "\n" + TENANT_FOOTER;
	}

	static String day(LocalDate date) {
		return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
	}

}
