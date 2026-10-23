package com.rentkeeper.billing.web;

import org.springframework.validation.BindingResult;

import com.rentkeeper.billing.application.ChargeEditService.ItemInput;
import com.rentkeeper.billing.application.ChargeViews.ItemRow;
import com.rentkeeper.billing.domain.ChargeItem;
import com.rentkeeper.billing.domain.ChargeItemType;
import com.rentkeeper.common.money.Won;

/** 받을 월세 항목 추가·고치기 폼 (M3-b 결정 10). 이름이 비어 있으면 종류 이름을 쓴다 */
public class ItemForm {

	private String type = ChargeItemType.OTHER.name();

	private String label = "";

	private String amount = "";

	private String memo = "";

	private long version;

	static ItemForm adding(long version) {
		ItemForm form = new ItemForm();
		form.version = version;
		return form;
	}

	static ItemForm editing(long version, ItemRow item) {
		ItemForm form = new ItemForm();
		form.version = version;
		form.type = item.type().name();
		form.label = item.label();
		form.amount = String.valueOf(item.amount());
		form.memo = item.memo() == null ? "" : item.memo();
		return form;
	}

	/** @return 형식이 틀리면 null (오류는 칸별로) */
	ItemInput toInput(BindingResult result) {
		ChargeItemType itemType = null;
		try {
			itemType = ChargeItemType.valueOf(type);
		} catch (IllegalArgumentException | NullPointerException e) {
			result.rejectValue("type", "invalid", "항목 종류를 골라 주세요");
		}
		String name = label == null ? "" : label.strip();
		if (name.isEmpty() && itemType != null) {
			name = itemType.getLabel();
		}
		if (name.codePointCount(0, name.length()) > ChargeItem.LABEL_MAX_LENGTH) {
			result.rejectValue("label", "tooLong", "이름은 50자 이하로 입력해 주세요");
		}
		Long value = Won.parse(amount);
		if (value == null) {
			result.rejectValue("amount", "invalid", "금액을 숫자로 입력해 주세요 (예: 30,000)");
		} else if (value > ChargeItem.AMOUNT_MAX) {
			result.rejectValue("amount", "tooLarge", "항목 금액은 1억 원까지예요");
		}
		String note = memo == null ? "" : memo.strip();
		if (note.codePointCount(0, note.length()) > ChargeItem.MEMO_MAX_LENGTH) {
			result.rejectValue("memo", "tooLong", "메모는 200자 이하로 입력해 주세요");
		}
		if (result.hasErrors()) {
			return null;
		}
		return new ItemInput(itemType, name, value, note);
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public String getAmount() {
		return amount;
	}

	public void setAmount(String amount) {
		this.amount = amount;
	}

	public String getMemo() {
		return memo;
	}

	public void setMemo(String memo) {
		this.memo = memo;
	}

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}

}
