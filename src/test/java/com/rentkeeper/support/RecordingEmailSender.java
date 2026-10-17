package com.rentkeeper.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rentkeeper.common.mail.EmailMessage;
import com.rentkeeper.common.mail.EmailSender;

/**
 * 테스트용: 보낸 메일을 기억해 두고, 본문의 링크에서 토큰을 꺼낼 수 있게 한다.
 * {@link RecordingEmailSenderConfiguration}로 등록한다.
 */
public class RecordingEmailSender implements EmailSender {

	private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

	private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

	@Override
	public void send(EmailMessage message) {
		sent.add(message);
	}

	public List<EmailMessage> sent() {
		return List.copyOf(sent);
	}

	public List<EmailMessage> sentTo(String to) {
		return sent.stream().filter(m -> m.to().equals(to)).toList();
	}

	public EmailMessage lastTo(String to) {
		List<EmailMessage> messages = sentTo(to);
		if (messages.isEmpty()) {
			throw new AssertionError("no mail sent to " + to);
		}
		return messages.get(messages.size() - 1);
	}

	/** 마지막 메일 본문의 링크에서 token 값을 꺼낸다 */
	public String lastTokenTo(String to) {
		Matcher matcher = TOKEN.matcher(lastTo(to).body());
		if (!matcher.find()) {
			throw new AssertionError("no token link in mail to " + to);
		}
		return matcher.group(1);
	}

	public void clear() {
		sent.clear();
	}

}
