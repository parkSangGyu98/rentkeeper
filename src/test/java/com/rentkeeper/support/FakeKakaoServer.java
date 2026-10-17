package com.rentkeeper.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 가짜 카카오 서버 (JDK 내장 HttpServer, 새 의존성 없음). 토큰 발급, 사용자 정보, 연결 끊기만 흉내 낸다.
 * <p>
 * 진짜 카카오 대신 이 서버로 주소를 바꾸면 인가 리다이렉트 → state 검증 → 토큰 교환 → 사용자 정보 → 연결 끊기를 실제 HTTP
 * 경로 그대로 시험할 수 있다. 실제 카카오 계정 확인은 개발자가 브라우저로 한다.
 */
public final class FakeKakaoServer {

	public record Request(String method, String path, String authorization, Map<String, String> form) {
	}

	private final HttpServer server;

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private volatile String userJson = user("1000000001", null, false, false, "김카카오");

	private volatile int unlinkStatus = 200;

	private volatile String unlinkFailureBody;

	private FakeKakaoServer(HttpServer server) {
		this.server = server;
	}

	public static FakeKakaoServer start() {
		try {
			HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			FakeKakaoServer fake = new FakeKakaoServer(http);
			http.createContext("/oauth/token", fake::token);
			http.createContext("/v2/user/me", fake::userInfo);
			http.createContext("/v1/user/unlink", fake::unlink);
			http.start();
			return fake;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** 다음 로그인에서 돌려줄 사용자 정보 */
	public void returnUser(String json) {
		this.userJson = json;
	}

	/** 연결 끊기 응답 상태 (실패 재시도 시험용). 실패 본문은 {"msg": "fake failure", "code": -1} */
	public void unlinkStatus(int status) {
		unlinkResponse(status, status == 200 ? null : "{\"msg\": \"fake failure\", \"code\": -1}");
	}

	/** 연결 끊기 실패 응답의 상태와 본문을 직접 정한다 (예: 400 + code -101) */
	public void unlinkResponse(int status, String failureBody) {
		this.unlinkStatus = status;
		this.unlinkFailureBody = failureBody;
	}

	public List<Request> requests() {
		return List.copyOf(requests);
	}

	public List<Request> requestsTo(String path) {
		return requests.stream().filter(r -> r.path().equals(path)).toList();
	}

	public void reset() {
		requests.clear();
		unlinkStatus = 200;
		unlinkFailureBody = null;
		userJson = user("1000000001", null, false, false, "김카카오");
	}

	/** 카카오 사용자 정보 응답 JSON (필요한 필드만) */
	public static String user(String id, String email, boolean valid, boolean verified, String nickname) {
		String emailPart = email == null ? "" : """
				"email": "%s", "is_email_valid": %s, "is_email_verified": %s,""".formatted(email, valid, verified);
		String profilePart = nickname == null ? "" : """
				"profile": {"nickname": "%s"},""".formatted(nickname);
		return """
				{"id": %s, "kakao_account": {%s %s "has_email": %s}}
				""".formatted(id, emailPart, profilePart, email != null);
	}

	private void token(HttpExchange exchange) throws IOException {
		record(exchange);
		respond(exchange, 200, """
				{"access_token": "fake-access-token", "token_type": "bearer", "expires_in": 21599,
				 "refresh_token": "fake-refresh-token", "refresh_token_expires_in": 5183999}
				""");
	}

	private void userInfo(HttpExchange exchange) throws IOException {
		record(exchange);
		respond(exchange, 200, userJson);
	}

	private void unlink(HttpExchange exchange) throws IOException {
		Request request = record(exchange);
		respond(exchange, unlinkStatus,
				unlinkStatus == 200 ? "{\"id\": " + request.form().get("target_id") + "}" : unlinkFailureBody);
	}

	private Request record(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, String> form = body.isBlank()
				? Map.of()
				: Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2)).collect(
						Collectors.toMap(p -> decode(p[0]), p -> p.length > 1 ? decode(p[1]) : "", (a, b) -> b));
		Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Authorization"), form);
		requests.add(request);
		return request;
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = (json == null ? "" : json).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

}
