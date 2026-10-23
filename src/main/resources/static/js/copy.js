// 복사 버튼 (M4: 세입자 링크·계좌번호). CSP(script-src 'self') 때문에 인라인 스크립트 대신 이 파일을 쓴다.
// <button data-copy-target="입력칸·글자 id" data-copy-done="안내를 보일 id">
// 복사가 안 되는 브라우저에서는 글자를 선택해 두어 길게 눌러 복사할 수 있게 한다.
(function () {
	'use strict';

	function textOf(element) {
		return element.value !== undefined ? element.value : element.textContent.trim();
	}

	function select(element) {
		if (element.select) {
			element.select();
			return;
		}
		var range = document.createRange();
		range.selectNodeContents(element);
		var selection = window.getSelection();
		selection.removeAllRanges();
		selection.addRange(range);
	}

	function show(doneId, message) {
		var done = doneId && document.getElementById(doneId);
		if (done) {
			done.textContent = message;
		}
	}

	document.addEventListener('click', function (event) {
		var button = event.target.closest('[data-copy-target]');
		if (!button) {
			return;
		}
		var target = document.getElementById(button.getAttribute('data-copy-target'));
		if (!target) {
			return;
		}
		var doneId = button.getAttribute('data-copy-done');
		var text = textOf(target);
		if (navigator.clipboard && window.isSecureContext) {
			navigator.clipboard.writeText(text).then(function () {
				show(doneId, '복사했어요');
			}, function () {
				select(target);
				show(doneId, '글자를 길게 눌러 복사해 주세요');
			});
			return;
		}
		select(target);
		show(doneId, '글자를 길게 눌러 복사해 주세요');
	});
}());
