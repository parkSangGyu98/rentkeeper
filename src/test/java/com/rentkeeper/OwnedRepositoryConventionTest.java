package com.rentkeeper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

/**
 * 임대인 소유 데이터의 저장소 규칙 (ADR-018). 이 규칙이 있어 소유권 확인을 빠뜨리는 코드를 쓸 수 없다.
 * <ul>
 * <li>{@code CrudRepository}·{@code JpaRepository}를 상속하지 않는다:
 * {@code findById}·{@code findAll}처럼 landlordId 없이 꺼내는 메서드가 없다</li>
 * <li>저장·삭제(엔티티를 받음)와 flush를 뺀 모든 메서드는 {@code landlordId} 인자를 받는다</li>
 * </ul>
 * 대상: property·lease 모듈(앞으로 생길 billing 등 임대인 소유 모듈도 여기에 추가한다).
 */
class OwnedRepositoryConventionTest {

	private static final String[] OWNED_MODULES = {"com.rentkeeper.property", "com.rentkeeper.lease"};

	private static final Set<String> ENTITY_METHODS = Set.of("save", "saveAndFlush", "delete", "flush");

	@Test
	void ownedRepositoriesCannotLoadWithoutLandlordId() throws Exception {
		List<Class<?>> repositories = repositories();
		assertThat(repositories).as("검사할 저장소가 있어야 한다").isNotEmpty();

		List<String> violations = new ArrayList<>();
		for (Class<?> repository : repositories) {
			if (CrudRepository.class.isAssignableFrom(repository)
					|| QueryByExampleExecutor.class.isAssignableFrom(repository)) {
				violations.add(repository.getSimpleName() + ": CrudRepository/JpaRepository 상속 금지");
			}
			for (Method method : repository.getDeclaredMethods()) {
				if (ENTITY_METHODS.contains(method.getName())) {
					continue;
				}
				boolean hasLandlordId = false;
				for (Parameter parameter : method.getParameters()) {
					hasLandlordId |= parameter.getName().equals("landlordId");
				}
				if (!hasLandlordId) {
					violations.add(repository.getSimpleName() + "." + method.getName() + ": landlordId 인자 없음");
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void parameterNamesAreAvailable() throws Exception {
		// -parameters 컴파일 옵션이 없으면 인자 이름이 arg0이 되어 위 검사가 의미 없어진다
		Method method = OwnedRepositoryConventionTest.class.getDeclaredMethod("sample", long.class);
		assertThat(method.getParameters()[0].getName()).isEqualTo("landlordId");
	}

	@SuppressWarnings("unused")
	private static void sample(long landlordId) {
	}

	private static List<Class<?>> repositories() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(
					org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
				return definition.getMetadata().isInterface();
			}
		};
		scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
		List<Class<?>> found = new ArrayList<>();
		for (String module : OWNED_MODULES) {
			for (var candidate : scanner.findCandidateComponents(module)) {
				found.add(Class.forName(candidate.getBeanClassName()));
			}
		}
		return found;
	}

}
