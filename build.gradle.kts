import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
	java
	id("org.springframework.boot") version "4.1.1"
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
