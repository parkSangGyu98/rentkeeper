import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("com.diffplug.spotless") version "8.10.3"
}

group = "com.rentkeeper"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		// Gradle을 실행하는 JVM(17 이상이면 됨)과 별개로, 컴파일·테스트는 항상 Java 21로 한다
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// ADR-002: 로컬 실행과 테스트도 운영과 같은 JVM 기본 시간대를 쓴다
val seoulTimeZone = "-Duser.timezone=Asia/Seoul"

dependencies {
	// 라이브러리 버전은 Boot BOM에 맡긴다 (io.spring.dependency-management 대신 Gradle 기본 platform 사용)
	implementation(platform(SpringBootPlugin.BOM_COORDINATES))

	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	// Boot 4부터 Flyway 자동 구성은 전용 스타터가 있어야 동작한다
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	jvmArgs(seoulTimeZone)
}

tasks.named<BootRun>("bootRun") {
	jvmArgs(seoulTimeZone)
}

// spotlessCheck는 check(=build)에 연결된다. 포맷이 어긋나면 빌드가 실패하고, spotlessApply로 고친다.
spotless {
	java {
		importOrder("java", "javax", "org", "com", "")
		removeUnusedImports()
		// 개발자가 쓰는 Eclipse(2025-06 = 4.36)와 같은 JDT 버전으로 포맷해 IDE 저장 결과와 일치시킨다
		eclipse("4.36").configFile("config/formatter/eclipse-formatter.xml")
	}
	format("misc") {
		target("*.md", "docs/**/*.md", "*.gradle.kts", "**/*.yml", ".gitignore", ".gitattributes")
		// PRD 사본은 원본과의 diff를 최소로 유지하기 위해 손대지 않는다
		targetExclude("docs/PRD.md", "build/**")
		trimTrailingWhitespace()
		endWithNewline()
	}
}
