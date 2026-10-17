package com.rentkeeper.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 키가 없으면 local이든 prod든 앱이 뜨지 않는다 (임시 키를 만들지 않는다, ADR-017).
 */
class EncryptionStartupTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(CryptoOnly.class);

	@Test
	void failsToStartWithoutKeysInAnyProfile() {
		for (String profile : new String[]{"local", "prod"}) {
			runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().isInstanceOf(EncryptionKeyException.class)
						.hasMessageContaining("APP_ENCRYPTION_KEY");
			});
		}
	}

	@Test
	void startsWithValidKeys() {
		runner.withPropertyValues("app.encryption.key=" + FieldEncryptorTest.KEY_V1, "app.encryption.key-version=1",
				"app.encryption.hmac-key=" + FieldEncryptorTest.KEY_V2).run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(FieldEncryptor.class).currentVersion()).isEqualTo(1);
				});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(EncryptionProperties.class)
	@Import({FieldEncryptor.class, KeyedHasher.class})
	static class CryptoOnly {

	}

}
