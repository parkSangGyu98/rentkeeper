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
 * 대상: property·lease·billing 모듈(앞으로 생길 임대인 소유 모듈도 여기에 추가한다).
 */
class OwnedRepositoryConventionTest {

	private static final String[] OWNED_MODULES = {"com.rentkeeper.property", "com.rentkeeper.lease",
			"com.rentkeeper.billing", "com.rentkeeper.notification"};

	private static final Set<String> ENTITY_METHODS = Set.of("save", "saveAndFlush", "delete", "flush");

	/**
	 * landlordId 없이 찾아도 되는 메서드. 세입자 링크의 입구 하나뿐이다: 세입자는 로그인하지 않아 landlordId가 없고,
	 * 256비트 토큰의 해시로만 찾는다. 찾은 행의 landlordId·leaseId로 이후 조회를 한정한다(ADR-026). 그리고 알림 발송
	 * 워커(M5): 모든 임대인의 알림을 ID로 차례로 보내고 운영 알림에는 임대인이 없다. 화면에서는 쓰지 않는다. 여기에 더할 때는 이유가
	 * 분명해야 한다.
	 */
	private static final Set<String> TOKEN_ENTRY_METHODS = Set.of("TenantAccessTokenRepository.findByTokenHash",
			"NotificationRepository.findForWorkerById", "NotificationRepository.findForWorkerByIdIn");

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
				if (ENTITY_METHODS.contains(method.getName())
						|| TOKEN_ENTRY_METHODS.contains(repository.getSimpleName() + "." + method.getName())) {
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
