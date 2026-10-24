package com.rentkeeper.lease.web;

import java.util.Set;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.lease.application.LeaseExceptions.TenantLinkExpiredException;
import com.rentkeeper.lease.application.LeaseExceptions.TenantLinkStaleException;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseSummary;
import com.rentkeeper.lease.application.TenantLinkService;
import com.rentkeeper.lease.application.TenantLinkService.IssuedLink;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 세입자 링크 만들기·새로 만들기·닫기 (계약 상세의 "세입자 링크", M4 결정 1~3)와 세입자 알림 다시 켜기 (M5 결정 8). 남의
 * 계약이면 404.
 * <p>
 * 새로 만든 링크는 리다이렉트하지 않고 POST 응답 화면에 바로 그린다. flash에 넣으면 원문이 세션(DB)에 잠깐이라도 남는다.
 */
@Controller
public class TenantLinkController {

	/** 계약 상세의 세입자 링크 구역에 보일 안내 */
	static final String ALERT = "tenantLinkAlert";

	static final String STALE_ISSUE = "이미 새 링크가 만들어졌어요. 방금 만든 링크는 다시 볼 수 없어요. 필요하면 새로 만들어 주세요";

	static final String STALE_CLOSE = "링크가 이미 바뀌었거나 닫혔어요. 아래 최신 상태를 확인해 주세요.";

	static final String EXPIRED = "계약이 끝나고 90일이 지나 세입자 링크를 만들 수 없어요.";

	static final String CLOSED = "세입자 링크를 닫았어요. 세입자는 예전 링크로 더 이상 들어올 수 없어요. 세입자 안내 문자도 멈췄어요.";

	static final String RESUMED = "세입자 안내 문자를 다시 켰어요. 다음 안내 문자부터 새 링크가 들어가요.";

	private final TenantLinkService tenantLinkService;

	private final LeaseService leaseService;

	private final AppProperties appProperties;

	public TenantLinkController(TenantLinkService tenantLinkService, LeaseService leaseService,
			AppProperties appProperties) {
		this.tenantLinkService = tenantLinkService;
		this.leaseService = leaseService;
		this.appProperties = appProperties;
	}

	/**
	 * 새 링크를 만들어 바로 보여 준다.
	 *
	 * @param openLinkId
	 *            화면이 보던 열린 링크 ID (없었으면 빈 값, M4 결정 2)
	 */
	@PostMapping("/app/leases/{id}/tenant-link")
	public String issue(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@RequestParam(name = "openLinkId", defaultValue = "") String openLinkId, Model model,
			HttpServletResponse response, RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		IssuedLink issued;
		try {
			issued = tenantLinkService.issue(landlordId, id, parseLinkId(openLinkId));
		} catch (TenantLinkStaleException e) {
			return backToLease(id, redirect, STALE_ISSUE);
		} catch (TenantLinkExpiredException e) {
			return backToLease(id, redirect, EXPIRED);
		}
		// 원문이 든 화면: 브라우저·프록시가 저장하지 않게 한다
		response.setHeader("Cache-Control", "no-store");
		model.addAttribute("lease", summary(landlordId, id));
		model.addAttribute("link", appProperties.tenantLink(issued.rawToken()));
		model.addAttribute("usableUntil", issued.usableUntil());
		return "lease/tenant-link-issued";
	}

	/** [새 링크 만들기] 확인 화면 (예전 링크가 닫힌다는 것을 알린다) */
	@GetMapping("/app/leases/{id}/tenant-link/reissue")
	public String reissueConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			Model model) {
		return confirmPage(principal.getLandlordId(), id, model, "lease/tenant-link-reissue");
	}

	/** [링크 닫기] 확인 화면 */
	@GetMapping("/app/leases/{id}/tenant-link/close")
	public String closeConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			Model model) {
		return confirmPage(principal.getLandlordId(), id, model, "lease/tenant-link-close");
	}

	@PostMapping("/app/leases/{id}/tenant-link/close")
	public String close(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@RequestParam(name = "openLinkId", defaultValue = "") String openLinkId, RedirectAttributes redirect) {
		try {
			tenantLinkService.close(principal.getLandlordId(), id, parseLinkId(openLinkId));
		} catch (TenantLinkStaleException e) {
			return backToLease(id, redirect, STALE_CLOSE);
		}
		redirect.addFlashAttribute("notice", CLOSED);
		return "redirect:/app/leases/" + id;
	}

	/** [알림 다시 켜기] (M5 결정 8). [링크 닫기]로 멈춘 세입자 알림을 다시 보낸다 */
	@PostMapping("/app/leases/{id}/tenant-notifications/resume")
	public String resumeNotifications(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			RedirectAttributes redirect) {
		tenantLinkService.resumeNotifications(principal.getLandlordId(), id);
		redirect.addFlashAttribute("notice", RESUMED);
		return "redirect:/app/leases/" + id + "#tenant-link";
	}

	private String confirmPage(long landlordId, long leaseId, Model model, String view) {
		// 남의 계약이면 여기서 404
		model.addAttribute("tenantLink", tenantLinkService.status(landlordId, leaseId));
		model.addAttribute("lease", summary(landlordId, leaseId));
		return view;
	}

	private LeaseSummary summary(long landlordId, long leaseId) {
		LeaseSummary summary = leaseService.summaries(landlordId, Set.of(leaseId)).get(leaseId);
		if (summary == null) {
			throw new ResourceNotFoundException("lease");
		}
		return summary;
	}

	private static String backToLease(long id, RedirectAttributes redirect, String alert) {
		redirect.addFlashAttribute(ALERT, alert);
		return "redirect:/app/leases/" + id + "#tenant-link";
	}

	/** 숨은 칸 값. 빈 값이면 "열린 링크 없음", 숫자가 아니면 어떤 링크와도 같지 않은 값 */
	private static Long parseLinkId(String raw) {
		if (raw.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(raw.strip());
		} catch (NumberFormatException e) {
			return -1L;
		}
	}

}
