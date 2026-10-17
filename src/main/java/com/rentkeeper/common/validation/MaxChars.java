package com.rentkeeper.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 글자(코드 포인트) 수 상한. PostgreSQL {@code varchar(n)}과 같은 기준이다.
 * <p>
 * {@code @Size}와 HTML {@code maxlength}는 UTF-16 단위로 세서 이모지 한 글자를 2로 센다. 그러면
 * DB에는 들어가는 이모지 500자 메모를 앱이 거부하게 되므로 입력 길이 검증에는 이것을 쓴다. 앞뒤 공백은 저장 전에 지우므로 빼고
 * 센다. null·빈 값은 통과한다(필수 여부는 {@code @NotBlank}로).
 */
@Documented
@Constraint(validatedBy = MaxChars.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxChars {

	int value();

	String message();

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<MaxChars, CharSequence> {

		private int max;

		@Override
		public void initialize(MaxChars annotation) {
			this.max = annotation.value();
		}

		@Override
		public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
			if (value == null) {
				return true;
			}
			String stripped = value.toString().strip();
			return stripped.codePointCount(0, stripped.length()) <= max;
		}

	}

}
