package com.rentkeeper.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import com.rentkeeper.config.ClockConfig;

/**
 * 테스트에서 시간을 앞으로 돌릴 수 있는 Clock (만료·잠금 해제 확인용).
 * {@link MutableClockConfiguration}으로 등록한다.
 */
public class MutableClock extends Clock {

	private volatile Instant now;

	private final ZoneId zone;

	public MutableClock(Instant start) {
		this(start, ClockConfig.SEOUL);
	}

	private MutableClock(Instant start, ZoneId zone) {
		this.now = start;
		this.zone = zone;
	}

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	public void set(Instant instant) {
		now = instant;
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(now, zone);
	}

}
