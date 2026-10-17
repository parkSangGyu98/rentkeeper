plugins {
	// 로컬에 맞는 JDK가 없을 때만 toolchain JDK를 자동으로 내려받는다 (로컬 설치 JDK를 먼저 탐지)
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "rentkeeper"
