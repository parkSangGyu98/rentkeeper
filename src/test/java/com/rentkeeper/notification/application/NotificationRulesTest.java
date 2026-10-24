package com.rentkeeper.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.notification.application.NotificationRules.Planned;
import com.rentkeeper.notification.domain.NotificationType;

/** 세입자 알림 규칙 (M5 결정 4·5): 누락 복구분 금지, 전날 1회, D+1·D+7, 세입자 신고 제외, 금지어 없는 문구 */
class NotificationRulesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

	@Test
	void issueNoticeOnlyWhenTheDueDateIsTodayOrLater() {
		assertThat(NotificationRules.noticeOnIssue(TODAY, TODAY)).isTrue();
		assertThat(NotificationRules.noticeOnIssue(TODAY.plusDays(8), TODAY)).isTrue();
		assertThat(NotificationRules.noticeOnIssue(TODAY.minusDays(1), TODAY)).as("누락 복구분").isFalse();
	}

	@Test
	void reminderTheDayBeforeOnly() {
		assertThat(NotificationRules.plan(TODAY, 1, TODAY.plusDays(1), ChargeStatus.ISSUED))
				.contains(new Planned(NotificationType.DUE_REMINDER, "DUE_REMINDER:1"));
		assertThat(NotificationRules.plan(TODAY, 1, TODAY.plusDays(1), ChargeStatus.PARTIALLY_PAID)).isPresent();
		assertThat(NotificationRules.plan(TODAY, 1, TODAY, ChargeStatus.ISSUED)).as("당일은 보내지 않는다").isEmpty();
		assertThat(NotificationRules.plan(TODAY, 1, TODAY.plusDays(2), ChargeStatus.ISSUED)).isEmpty();
		for (ChargeStatus status : List.of(ChargeStatus.TENANT_REPORTED, ChargeStatus.PAID, ChargeStatus.CANCELED,
				ChargeStatus.SCHEDULED)) {
			assertThat(NotificationRules.plan(TODAY, 1, TODAY.plusDays(1), status)).as(status.name()).isEmpty();
		}
	}

	@Test
	void overdueNoticesOnDayOneAndSevenOnlyAndNeverForTenantReports() {
		assertThat(NotificationRules.plan(TODAY, 9, TODAY.minusDays(1), ChargeStatus.OVERDUE))
				.contains(new Planned(NotificationType.OVERDUE_NOTICE, "OVERDUE_NOTICE:9:D1"));
		assertThat(NotificationRules.plan(TODAY, 9, TODAY.minusDays(7), ChargeStatus.OVERDUE))
				.contains(new Planned(NotificationType.OVERDUE_NOTICE, "OVERDUE_NOTICE:9:D7"));
		for (int days : new int[]{2, 3, 6, 8, 14}) {
			assertThat(NotificationRules.plan(TODAY, 9, TODAY.minusDays(days), ChargeStatus.OVERDUE)).as("D+" + days)
					.isEmpty();
		}
		assertThat(NotificationRules.plan(TODAY, 9, TODAY.minusDays(1), ChargeStatus.TENANT_REPORTED))
				.as("세입자가 입금했다고 알린 것은 빠진다").isEmpty();
		assertThat(NotificationRules.plannedStatuses()).doesNotContain(ChargeStatus.TENANT_REPORTED);
	}

	@Test
	void staleWhenTheStatusChangedBeforeSending() {
		assertThat(NotificationRules.stillValid(NotificationType.DUE_REMINDER, ChargeStatus.ISSUED)).isTrue();
		assertThat(NotificationRules.stillValid(NotificationType.DUE_REMINDER, ChargeStatus.PAID)).isFalse();
		assertThat(NotificationRules.stillValid(NotificationType.CHARGE_ISSUED, ChargeStatus.TENANT_REPORTED))
				.isFalse();
		assertThat(NotificationRules.stillValid(NotificationType.OVERDUE_NOTICE, ChargeStatus.OVERDUE)).isTrue();
		assertThat(NotificationRules.stillValid(NotificationType.OVERDUE_NOTICE, ChargeStatus.TENANT_REPORTED))
				.isFalse();
	}

	@Test
	void overdueBeatsReminderBeatsIssued() {
		assertThat(NotificationRules.priority(NotificationType.OVERDUE_NOTICE))
				.isGreaterThan(NotificationRules.priority(NotificationType.DUE_REMINDER));
		assertThat(NotificationRules.priority(NotificationType.DUE_REMINDER))
				.isGreaterThan(NotificationRules.priority(NotificationType.CHARGE_ISSUED));
	}

	@Test
	void textsUseNoHarshWordsAndCarryTheFooter() {
		YearMonth october = YearMonth.of(2026, 10);
		String link = "http://localhost:8080/t/xyz";
		List<String> texts = List.of(
				NotificationTemplates.chargeIssued("행복빌라 101호", october, 550_000, TODAY.plusDays(8), link),
				NotificationTemplates.dueReminder("행복빌라 101호", october, TODAY.plusDays(1), link),
				NotificationTemplates.overdueFirst("행복빌라 101호", october, link),
				NotificationTemplates.overdueSecond("행복빌라 101호", october, 300_000, link));

		for (String text : texts) {
			assertThat(text).doesNotContain("연체").doesNotContain("미납").doesNotContain("독촉").startsWith("[행복빌라 101호] ")
					.contains(link).endsWith(NotificationTemplates.TENANT_FOOTER);
		}
		assertThat(texts.get(0)).contains("10월 월세 안내드립니다. 보내실 금액 550,000원, 보내실 날짜 10월 25일까지입니다.");
		assertThat(texts.get(1)).contains("내일(10월 18일)은 10월 월세를 보내시는 날입니다.");
		assertThat(texts.get(3)).contains("10월 월세 300,000원의 입금 확인을 기다리고 있습니다.");
	}

}
