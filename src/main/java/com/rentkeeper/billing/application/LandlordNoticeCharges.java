package com.rentkeeper.billing.application;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.domain.ChargeStatus;

/** 임대인 요약 메일에 쓰는 받을 월세 건수 (M5 결정 4). 건수와 ID만 돌려준다 */
@Service
@Transactional(readOnly = true)
public class LandlordNoticeCharges {

	private final JdbcTemplate jdbc;

	LandlordNoticeCharges(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 세입자가 입금했다고 알려 확인을 기다리는 받을 월세 수 */
	public int tenantReported(long landlordId) {
		return jdbc.queryForObject("select count(*) from charge where landlord_id = ? and status = ?", Integer.class,
				landlordId, ChargeStatus.TENANT_REPORTED.name());
	}

	/** 그날이 납부일이었는데 납부일 지남이 된 받을 월세가 있는 임대인 (모든 임대인, 계획 배치용) */
	public List<Long> landlordsWithOverdueDueOn(LocalDate dueDate) {
		return jdbc.queryForList(
				"select distinct landlord_id from charge where status = ? and due_date = ? order by landlord_id",
				Long.class, ChargeStatus.OVERDUE.name(), Date.valueOf(dueDate));
	}

	/** 그날이 납부일이었는데 아직 납부일 지남인 받을 월세 수 */
	public int overdueDueOn(long landlordId, LocalDate dueDate) {
		return jdbc.queryForObject("select count(*) from charge where landlord_id = ? and status = ? and due_date = ?",
				Integer.class, landlordId, ChargeStatus.OVERDUE.name(), Date.valueOf(dueDate));
	}

}
