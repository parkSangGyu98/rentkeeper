package com.rentkeeper.lease.application;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.application.PropertyViews.UnitView;

/**
 * 곧 끝나는 계약 (NOTI-5, M5 결정 4). 진행 중인 계약의 나가는 날(end_date)이 정한 날들 중 하나인 것. 세입자
 * 이름·연락처는 담지 않는다(임대인 메일에도 넣지 않는다).
 */
@Service
@Transactional(readOnly = true)
public class LeaseEndNotices {

	private final JdbcTemplate jdbc;

	private final PropertyService propertyService;

	LeaseEndNotices(JdbcTemplate jdbc, PropertyService propertyService) {
		this.jdbc = jdbc;
		this.propertyService = propertyService;
	}

	/** 메일 한 줄: 호실과 나가는 날 */
	public record LeaseEnding(long leaseId, String unitName, LocalDate endDate) {
	}

	/** 나가는 날이 endDates 중 하나인 진행 중 계약이 있는 임대인 (모든 임대인, 계획 배치용) */
	public List<Long> landlordsEndingOn(Collection<LocalDate> endDates) {
		if (endDates.isEmpty()) {
			return List.of();
		}
		return jdbc.queryForList("select distinct landlord_id from lease where status = 'ACTIVE' and end_date in ("
				+ placeholders(endDates.size()) + ") order by landlord_id", Long.class, dates(endDates));
	}

	/** 그 임대인의 나가는 날이 endDates 중 하나인 진행 중 계약 (나가는 날 순) */
	public List<LeaseEnding> endingOn(long landlordId, Collection<LocalDate> endDates) {
		if (endDates.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>();
		args.add(landlordId);
		args.addAll(List.of(dates(endDates)));
		List<long[]> rows = jdbc.query(
				"select id, unit_id, end_date from lease where landlord_id = ? and status = 'ACTIVE' and end_date in ("
						+ placeholders(endDates.size()) + ") order by end_date, id",
				(rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate().toEpochDay()},
				args.toArray());
		Map<Long, UnitView> units = new HashMap<>();
		List<LeaseEnding> endings = new ArrayList<>();
		for (long[] row : rows) {
			UnitView unit = units.computeIfAbsent(row[1], unitId -> propertyService.unit(landlordId, unitId));
			endings.add(new LeaseEnding(row[0], unit.displayName(), LocalDate.ofEpochDay(row[2])));
		}
		return endings;
	}

	private static Object[] dates(Collection<LocalDate> dates) {
		return dates.stream().map(Date::valueOf).toArray();
	}

	private static String placeholders(int count) {
		return String.join(", ", Collections.nCopies(count, "?"));
	}

}
