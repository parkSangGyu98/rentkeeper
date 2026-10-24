package com.rentkeeper;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;

import org.springframework.data.repository.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Entity;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 모듈 의존 방향과 시간 규칙 (ADR-020, PRD 10장).
 *
 * <pre>
 * tenantlink·dashboard → billing → lease → property → landlord ← auth → consent → site
 *                 (모두) → common          config → (모두)
 * </pre>
 *
 * 도메인 모듈(property·lease·billing)의 화면은 로그인 정보(auth.security)만 쓸 수 있다. 세입자
 * 화면(tenantlink)은 로그인 정보를 전혀 쓰지 않는다. 다른 모듈의 엔티티·리포지토리는 쓰지 않는다(서비스와 값 타입·예외만).
 */
@AnalyzeClasses(packages = "com.rentkeeper", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

	private static final String ROOT = "com.rentkeeper.";

	// ---------------------------------------------------------------- 의존 방향

	@ArchTest
	static final ArchRule commonDependsOnNoModule = noClasses().that().resideInAPackage(ROOT + "common..").should()
			.dependOnClassesThat().resideInAnyPackage(modules("auth", "landlord", "property", "lease", "billing",
					"consent", "site", "config", "tenantlink", "dashboard", "notification", "demo"))
			.because("common은 모든 모듈이 쓰는 공통 부품이다");

	@ArchTest
	static final ArchRule landlordDependsOnlyOnCommon = noClasses().that().resideInAPackage(ROOT + "landlord..")
			.should().dependOnClassesThat().resideInAnyPackage(modules("auth", "property", "lease", "billing",
					"consent", "site", "config", "tenantlink", "dashboard", "notification", "demo"))
			.because("임대인 계정은 다른 도메인이 기대는 맨 아래 모듈이다");

	@ArchTest
	static final ArchRule propertyDoesNotDependOnUpperModules = noClasses().that().resideInAPackage(ROOT + "property..")
			.should().dependOnClassesThat().resideInAnyPackage(modules("lease", "billing", "consent", "site", "config",
					"tenantlink", "dashboard", "notification", "demo"))
			.because("호실 상태 계산처럼 계약 정보가 필요하면 lease가 property를 부르거나 조회 결과를 넘긴다");

	@ArchTest
	static final ArchRule leaseDoesNotDependOnUpperModules = noClasses().that().resideInAPackage(ROOT + "lease..")
			.should().dependOnClassesThat()
			.resideInAnyPackage(
					modules("billing", "consent", "site", "config", "tenantlink", "dashboard", "notification", "demo"))
			.because("청구가 있는지 같은 정보가 필요하면 lease가 인터페이스를 정하고 billing이 구현한다(ChargedMonths)");

	@ArchTest
	static final ArchRule billingDoesNotDependOnUpperModules = noClasses().that().resideInAPackage(ROOT + "billing..")
			.should().dependOnClassesThat().resideInAnyPackage(
					modules("consent", "site", "config", "tenantlink", "dashboard", "notification", "demo"));

	@ArchTest
	static final ArchRule tenantLinkUsesNoLoginInfo = noClasses().that().resideInAPackage(ROOT + "tenantlink..")
			.should().dependOnClassesThat()
			.resideInAnyPackage(
					modules("auth", "landlord", "consent", "site", "config", "dashboard", "notification", "demo"))
			.because("세입자는 로그인하지 않는다. 링크의 토큰이 가리키는 계약 하나만 본다 (ADR-026)");

	@ArchTest
	static final ArchRule dashboardDoesNotDependOnTenantLink = noClasses().that().resideInAPackage(ROOT + "dashboard..")
			.should().dependOnClassesThat()
			.resideInAnyPackage(modules("tenantlink", "consent", "site", "config", "notification", "demo"))
			.because("대시보드는 임대인 화면이다. 세입자 화면과 서로 기대지 않는다");

	@ArchTest
	static final ArchRule domainModulesUseOnlyLoginInfoFromAuth = noClasses().that()
			.resideInAnyPackage(modules("property", "lease", "billing", "dashboard", "notification")).should()
			.dependOnClassesThat(
					resideInAPackage(ROOT + "auth..").and(resideOutsideOfPackage(ROOT + "auth.security..")))
			.because("화면에서 로그인한 임대인(LandlordPrincipal)만 쓴다");

	@ArchTest
	static final ArchRule nothingDependsOnConfig = noClasses().that().resideOutsideOfPackage(ROOT + "config..").should()
			.dependOnClassesThat().resideInAPackage(ROOT + "config..").because("config는 조립만 한다. 상수가 필요하면 그 모듈로 옮긴다");

	@ArchTest
	static final ArchRule demoDependsOnNoConfigOrSite = noClasses().that().resideInAPackage(ROOT + "demo..").should()
			.dependOnClassesThat().resideInAnyPackage(modules("site", "config", "tenantlink", "dashboard"))
			.because("데모는 맨 위 모듈이다: 서비스로 가짜 데이터를 넣고 로그인만 시킨다. 다른 모듈은 데모를 모른다 (M6 결정 3)");

	@ArchTest
	static final ArchRule notificationDependsOnNoUpperModules = noClasses().that()
			.resideInAPackage(ROOT + "notification..").should().dependOnClassesThat()
			.resideInAnyPackage(modules("tenantlink", "dashboard", "consent", "site", "config", "demo"))
			.because("알림은 billing·lease의 이벤트와 서비스만 쓴다. 다른 모듈은 알림을 모른다 (M5 결정 1)");

	// ---------------------------------------------------------------- 모듈 경계

	@ArchTest
	static final ArchRule noEntityOrRepositoryOfOtherModule = noClasses().should(useEntityOrRepositoryOfOtherModule())
			.because("다른 모듈은 서비스(application)와 값 타입·예외로만 쓴다 (PRD 10장)");

	@ArchTest
	static final ArchRule controllersDoNotUseRepositories = noClasses().that().areAnnotatedWith(Controller.class)
			.should().dependOnClassesThat().areAssignableTo(Repository.class)
			.because("controller → service → repository 단방향 (PRD 10장)");

	// ---------------------------------------------------------------- 배치

	@ArchTest
	static final ArchRule scheduledMethodsAreLocked = methods().that().areAnnotatedWith(Scheduled.class).should()
			.beAnnotatedWith(SchedulerLock.class).andShould().bePublic()
			.because("서버가 여러 대여도 배치는 한 곳에서만 돈다. 잠금 프록시가 확실히 가로채도록 public (ADR-022)");

	// ---------------------------------------------------------------- 시간

	@ArchTest
	static final ArchRule noArgumentlessNow = noClasses().should().callMethod(LocalDate.class, "now").orShould()
			.callMethod(LocalDateTime.class, "now").orShould().callMethod(LocalTime.class, "now").orShould()
			.callMethod(Instant.class, "now").orShould().callMethod(ZonedDateTime.class, "now").orShould()
			.callMethod(OffsetDateTime.class, "now").orShould().callMethod(YearMonth.class, "now").orShould()
			.callMethod(Year.class, "now").because("현재 시각은 주입한 Clock으로만 얻는다: now(clock) (ADR-002)");

	private static String[] modules(String... names) {
		String[] packages = new String[names.length];
		for (int i = 0; i < names.length; i++) {
			packages[i] = ROOT + names[i] + "..";
		}
		return packages;
	}

	/** 출발 클래스와 다른 최상위 모듈의 @Entity 또는 Spring Data 리포지토리에 기대는 의존 */
	private static ArchCondition<JavaClass> useEntityOrRepositoryOfOtherModule() {
		return new ArchCondition<>("use an entity or repository of another module") {
			@Override
			public void check(JavaClass origin, ConditionEvents events) {
				for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
					JavaClass target = dependency.getTargetClass();
					boolean entityOrRepository = target.isAnnotatedWith(Entity.class)
							|| target.isAssignableTo(Repository.class);
					String from = module(origin);
					String to = module(target);
					if (entityOrRepository && to != null && !to.equals(from)) {
						events.add(SimpleConditionEvent.satisfied(origin, dependency.getDescription()));
					}
				}
			}
		};
	}

	private static String module(JavaClass javaClass) {
		String name = javaClass.getPackageName();
		if (!name.startsWith(ROOT)) {
			return null;
		}
		String rest = name.substring(ROOT.length());
		int dot = rest.indexOf('.');
		return dot < 0 ? rest : rest.substring(0, dot);
	}

}
