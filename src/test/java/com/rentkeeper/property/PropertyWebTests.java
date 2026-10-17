package com.rentkeeper.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.TestData;

/**
 * 건물·호실 화면 (PROP-1~2): 빈 상태, 등록·수정·삭제, 중복 이름의 친절한 안내, 삭제 확인 화면, 다른 임대인 접근 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PropertyWebTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	Session a;

	Session b;

	@BeforeEach
	void setUp() throws Exception {
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		b = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "b@example.com");
	}

	@Test
	void emptyStateInvitesFirstProperty() throws Exception {
		mockMvc.perform(get("/app/properties").cookie(a.cookie())).andExpect(status().isOk())
				.andExpect(content().string(containsString("첫 건물을 등록해 보세요")));
	}

	@Test
	void createsPropertyWithNormalizedNameAndListsIt() throws Exception {
		long id = createProperty(a, "  행복   빌라 ");

		assertThat(jdbc.queryForMap("select landlord_id, name from property where id = ?", id))
				.containsEntry("landlord_id", a.landlordId()).containsEntry("name", "행복 빌라");
		mockMvc.perform(get("/app/properties").cookie(a.cookie())).andExpect(content().string(containsString("행복 빌라")))
				.andExpect(content().string(not(containsString("첫 건물을 등록해 보세요"))));
		mockMvc.perform(get("/app/properties").cookie(b.cookie()))
				.andExpect(content().string(not(containsString("행복 빌라"))));
	}

	@Test
	void duplicatePropertyNameShowsFriendlyMessage() throws Exception {
		createProperty(a, "행복빌라");

		mockMvc.perform(post("/app/properties").with(csrf()).cookie(a.cookie()).param("name", "행복빌라"))
				.andExpect(status().isOk()).andExpect(content().string(containsString("이미 같은 이름의 건물이 있어요")))
				.andExpect(content().string(not(containsString("uk_property_landlord_name"))))
				.andExpect(content().string(not(containsString("duplicate key"))));
		assertThat(count("property where landlord_id = " + a.landlordId())).isOne();

		// 다른 임대인은 같은 이름을 쓸 수 있다
		createProperty(b, "행복빌라");
	}

	@Test
	void validationErrorsAreShownWithoutEchoingValues() throws Exception {
		mockMvc.perform(post("/app/properties").with(csrf()).cookie(a.cookie()).param("name", "  "))
				.andExpect(status().isOk()).andExpect(content().string(containsString("건물 이름을 입력해 주세요")));
		mockMvc.perform(post("/app/properties").with(csrf()).cookie(a.cookie()).param("name", "가".repeat(51)))
				.andExpect(content().string(containsString("50자 이하")));
		assertThat(count("property")).isZero();
	}

	@Test
	void updatesProperty() throws Exception {
		long id = createProperty(a, "행복빌라");
		createProperty(a, "중앙상가");

		mockMvc.perform(get("/app/properties/" + id + "/edit").cookie(a.cookie()))
				.andExpect(content().string(containsString("value=\"행복빌라\"")));
		mockMvc.perform(post("/app/properties/" + id).with(csrf()).cookie(a.cookie()).param("name", "중앙상가"))
				.andExpect(content().string(containsString("이미 같은 이름의 건물이 있어요")));
		mockMvc.perform(post("/app/properties/" + id).with(csrf()).cookie(a.cookie()).param("name", "새빌라")
				.param("address", "서울시 어딘가").param("memo", "")).andExpect(redirectedUrl("/app/properties/" + id));

		assertThat(jdbc.queryForMap("select name, address, memo from property where id = ?", id))
				.containsEntry("name", "새빌라").containsEntry("address", "서울시 어딘가").containsEntry("memo", null);
	}

	@Test
	void addsUnitsWithFriendlyDuplicateMessage() throws Exception {
		long propertyId = createProperty(a, "행복빌라");
		long otherProperty = createProperty(a, "중앙상가");

		addUnit(a, propertyId, "101호").andExpect(redirectedUrl("/app/properties/" + propertyId));
		addUnit(a, propertyId, " 101호 ").andExpect(status().isOk())
				.andExpect(content().string(containsString("이 건물에 같은 이름의 호실이 이미 있어요")))
				.andExpect(content().string(not(containsString("uk_unit_property_name"))))
				.andExpect(content().string(containsString("value=\" 101호 \"")));
		// 다른 건물은 같은 호실 이름 가능
		addUnit(a, otherProperty, "101호").andExpect(redirectedUrl("/app/properties/" + otherProperty));

		mockMvc.perform(get("/app/properties/" + propertyId).cookie(a.cookie()))
				.andExpect(content().string(containsString("101호"))).andExpect(content().string(containsString("공실")))
				.andExpect(content().string(containsString("원룸")));
		assertThat(count("unit where property_id = " + propertyId)).isOne();
	}

	@Test
	void updatesAndDeletesUnit() throws Exception {
		long propertyId = createProperty(a, "행복빌라");
		addUnit(a, propertyId, "101호");
		addUnit(a, propertyId, "102호");
		long unitId = unitId(propertyId, "101호");

		mockMvc.perform(post("/app/units/" + unitId).with(csrf()).cookie(a.cookie()).param("name", "102호").param("type",
				"STORE")).andExpect(content().string(containsString("이 건물에 같은 이름의 호실이 이미 있어요")));
		mockMvc.perform(post("/app/units/" + unitId).with(csrf()).cookie(a.cookie()).param("name", "103호").param("type",
				"STORE")).andExpect(redirectedUrl("/app/properties/" + propertyId));
		assertThat(jdbc.queryForMap("select name, type from unit where id = ?", unitId)).containsEntry("name", "103호")
				.containsEntry("type", "STORE");

		mockMvc.perform(get("/app/units/" + unitId + "/delete").cookie(a.cookie()))
				.andExpect(content().string(containsString("호실을 지울까요?")));
		mockMvc.perform(post("/app/units/" + unitId + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(redirectedUrl("/app/properties/" + propertyId));
		assertThat(count("unit where id = " + unitId)).isZero();
	}

	@Test
	void propertyWithUnitsCannotBeDeleted() throws Exception {
		long propertyId = createProperty(a, "행복빌라");
		addUnit(a, propertyId, "101호");

		mockMvc.perform(get("/app/properties/" + propertyId + "/delete").cookie(a.cookie()))
				.andExpect(content().string(containsString("호실이 있는 건물은 지울 수 없어요")))
				.andExpect(content().string(not(containsString("네, 지울게요"))));
		mockMvc.perform(post("/app/properties/" + propertyId + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(redirectedUrl("/app/properties/" + propertyId))
				.andExpect(flash().attribute("notice", "호실이 있는 건물은 지울 수 없어요. 호실을 먼저 지워 주세요."));
		assertThat(count("property where id = " + propertyId)).isOne();

		long emptyProperty = createProperty(a, "빈건물");
		mockMvc.perform(post("/app/properties/" + emptyProperty + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(redirectedUrl("/app/properties"));
		assertThat(count("property where id = " + emptyProperty)).isZero();
	}

	@Test
	void otherLandlordsPropertyAndUnitAreNotFound() throws Exception {
		long propertyB = createProperty(b, "남의빌라");
		addUnit(b, propertyB, "101호");
		long unitB = unitId(propertyB, "101호");
		Map<String, Object> before = jdbc.queryForMap("select name, address from property where id = ?", propertyB);
		String notFoundBody = mockMvc.perform(get("/app/properties/999999999").cookie(a.cookie()))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

		for (String path : new String[]{"/app/properties/" + propertyB, "/app/properties/" + propertyB + "/edit",
				"/app/properties/" + propertyB + "/delete", "/app/units/" + unitB + "/edit",
				"/app/units/" + unitB + "/delete"}) {
			String body = mockMvc.perform(get(path).cookie(a.cookie())).andExpect(status().isNotFound()).andReturn()
					.getResponse().getContentAsString();
			assertThat(body).as(path + ": 없는 ID와 같은 응답").isEqualTo(notFoundBody).doesNotContain("남의빌라");
		}
		mockMvc.perform(post("/app/properties/" + propertyB).with(csrf()).cookie(a.cookie()).param("name", "탈취"))
				.andExpect(status().isNotFound());
		// 검증 오류여도 남의 건물이면 404
		mockMvc.perform(post("/app/properties/" + propertyB).with(csrf()).cookie(a.cookie()).param("name", ""))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/app/properties/" + propertyB + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(status().isNotFound());
		addUnit(a, propertyB, "999호").andExpect(status().isNotFound());
		mockMvc.perform(
				post("/app/units/" + unitB).with(csrf()).cookie(a.cookie()).param("name", "탈취").param("type", "OTHER"))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/app/units/" + unitB + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(status().isNotFound());

		assertThat(jdbc.queryForMap("select name, address from property where id = ?", propertyB)).isEqualTo(before);
		assertThat(count("unit where property_id = " + propertyB)).isOne();
		assertThat(jdbc.queryForObject("select name from unit where id = ?", String.class, unitB)).isEqualTo("101호");
	}

	@Test
	void landlordIdInRequestIsIgnored() throws Exception {
		mockMvc.perform(post("/app/properties").with(csrf()).cookie(a.cookie()).param("name", "행복빌라")
				.param("landlordId", String.valueOf(b.landlordId())));

		assertThat(jdbc.queryForObject("select landlord_id from property", Long.class)).isEqualTo(a.landlordId());
	}

	private long createProperty(Session session, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/app/properties").with(csrf()).cookie(session.cookie())
				.param("name", name).param("address", "").param("memo", "")).andReturn();
		String location = result.getResponse().getRedirectedUrl();
		assertThat(location).as("등록 성공").startsWith("/app/properties/");
		return Long.parseLong(location.substring("/app/properties/".length()));
	}

	private ResultActions addUnit(Session session, long propertyId, String name) throws Exception {
		return mockMvc.perform(post("/app/properties/" + propertyId + "/units").with(csrf()).cookie(session.cookie())
				.param("name", name).param("type", "ONE_ROOM").param("memo", ""));
	}

	private long unitId(long propertyId, String name) {
		return jdbc.queryForObject("select id from unit where property_id = ? and name = ?", Long.class, propertyId,
				name);
	}

	private int count(String fromWhere) {
		return jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class);
	}

}
