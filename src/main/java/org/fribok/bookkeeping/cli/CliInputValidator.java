package org.fribok.bookkeeping.cli;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Field;
import java.util.Collection;

/** Applies the same structural annotations that drive generated JSON Schemas. */
final class CliInputValidator {
    private CliInputValidator() {}

    static <T> T validate(T input) {
        validateObject(input, "$");
        return input;
    }

    private static void validateObject(Object value, String path) {
        if (value == null) {
            return;
        }
        for (Field field : value.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object fieldValue = field.get(value);
                String fieldPath = path + "." + field.getName();
                if (field.isAnnotationPresent(NotNull.class) && fieldValue == null) {
                    invalid(fieldPath, "is required");
                }
                if (field.isAnnotationPresent(NotBlank.class)
                        && !(fieldValue instanceof String string && !string.isBlank())) {
                    invalid(fieldPath, "must not be blank");
                }
                Size size = field.getAnnotation(Size.class);
                if (size != null && fieldValue instanceof Collection<?> collection
                        && (collection.size() < size.min() || collection.size() > size.max())) {
                    invalid(fieldPath, "must contain between " + size.min() + " and "
                            + size.max() + " items");
                }
                if (field.isAnnotationPresent(Positive.class) && fieldValue instanceof Number number
                        && new java.math.BigDecimal(number.toString()).signum() <= 0) {
                    invalid(fieldPath, "must be positive");
                }
                if (field.isAnnotationPresent(Valid.class)) {
                    if (fieldValue instanceof Collection<?> collection) {
                        int index = 0;
                        for (Object item : collection) {
                            validateObject(item, fieldPath + "[" + index++ + "]");
                        }
                    } else {
                        validateObject(fieldValue, fieldPath);
                    }
                }
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static void invalid(String path, String message) {
        throw new CliException("INPUT_INVALID", path + " " + message);
    }
}
