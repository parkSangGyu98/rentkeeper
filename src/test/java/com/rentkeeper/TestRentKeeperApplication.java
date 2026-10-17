package com.rentkeeper;

import org.springframework.boot.SpringApplication;

/**
 * docker compose 없이 Testcontainers DB로 앱을 띄우는 개발용 진입점. IDE에서 main을 실행하거나
 * {@code .\gradlew.bat bootTestRun}으로 실행한다.
 */
public class TestRentKeeperApplication {

	public static void main(String[] args) {
		SpringApplication.from(RentKeeperApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
