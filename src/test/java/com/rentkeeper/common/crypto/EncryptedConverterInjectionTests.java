package com.rentkeeper.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.resource.beans.spi.ManagedBeanRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.rentkeeper.TestcontainersConfiguration;

import jakarta.persistence.EntityManagerFactory;

/**
 * Hibernate 7이 컨버터를 Spring 빈 컨테이너로 만들어 생성자에 FieldEncryptor를 주입하는지 (ADR-017).
 * 엔티티의 {@code @Convert(converter = ...)}도 같은 경로(ManagedBeanRegistry)로 컨버터를 얻는다.
 * 안 되면 암호화 컨버터 설계가 성립하지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EncryptedConverterInjectionTests {

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Test
	void hibernateCreatesConverterWithInjectedEncryptor() {
		ManagedBeanRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getServiceRegistry()
				.requireService(ManagedBeanRegistry.class);

		ProbeConverter converter = registry.getBean(ProbeConverter.class).getBeanInstance();

		String stored = converter.convertToDatabaseColumn("홍길동");
		assertThat(stored).startsWith("v1:").doesNotContain("홍길동");
		assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("홍길동");
	}

	/** 시험용 컨버터 (Spring 빈으로 등록하지 않는다: Hibernate가 빈 컨테이너로 만들게 한다) */
	static class ProbeConverter extends EncryptedConverter<String> {

		ProbeConverter(FieldEncryptor encryptor) {
			super(encryptor, "test.probe");
		}

		@Override
		protected String toPlaintext(String value) {
			return value;
		}

		@Override
		protected String fromPlaintext(String plaintext) {
			return plaintext;
		}

	}

}
