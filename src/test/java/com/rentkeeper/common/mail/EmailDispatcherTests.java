package com.rentkeeper.common.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.RecordingEmailSenderConfiguration;

/**
 * 메일은 트랜잭션이 커밋된 뒤에만 나간다 (ADR-010).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RecordingEmailSenderConfiguration.class})
class EmailDispatcherTests {

	private static final EmailMessage MESSAGE = new EmailMessage("kim@example.com", "제목", "본문");

	@Autowired
	EmailDispatcher dispatcher;

	@Autowired
	RecordingEmailSender sender;

	@Autowired
	TransactionTemplate transactionTemplate;

	@BeforeEach
	void clear() {
		sender.clear();
	}

	@Test
	void sendsOnlyAfterCommit() {
		transactionTemplate.executeWithoutResult(status -> {
			dispatcher.sendAfterCommit(MESSAGE);
			assertThat(sender.sent()).as("커밋 전에는 보내지 않음").isEmpty();
		});

		assertThat(sender.sent()).containsExactly(MESSAGE);
	}

	@Test
	void doesNotSendWhenTransactionRollsBack() {
		transactionTemplate.executeWithoutResult(status -> {
			dispatcher.sendAfterCommit(MESSAGE);
			status.setRollbackOnly();
		});

		assertThat(sender.sent()).isEmpty();
	}

	@Test
	void sendsImmediatelyOutsideTransaction() {
		dispatcher.sendAfterCommit(MESSAGE);

		assertThat(sender.sent()).containsExactly(MESSAGE);
	}

}
