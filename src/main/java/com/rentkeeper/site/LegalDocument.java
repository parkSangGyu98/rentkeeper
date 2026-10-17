package com.rentkeeper.site;

/**
 * 약관·개인정보처리방침의 현재 버전. 화면에 표시하고, M1-c의 동의 기록(consent_log)에도 이 버전을 남긴다.
 * <p>
 * 현재 문서는 목차만 있는 초안이다. 출시 전 전문가 검토 후 내용과 시행일을 채운다 (docs/progress.md 출시 전 할 일).
 */
public enum LegalDocument {

	TERMS("이용약관", "v0.1"),

	PRIVACY("개인정보처리방침", "v0.1");

	private final String title;

	private final String version;

	LegalDocument(String title, String version) {
		this.title = title;
		this.version = version;
	}

	public String getTitle() {
		return title;
	}

	public String getVersion() {
		return version;
	}

}
