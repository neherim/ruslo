package io.github.neherim.ruslo.core.api.definition;

/**
 * Named, typed process variable.
 *
 * @param name  variable name
 * @param clazz value class
 */
public record VariableType<T>(String name, Class<T> clazz) {

    /**
     * @throws IllegalArgumentException if the name is blank or the class is generic
     */
    public VariableType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Variable name cannot be blank");
        }
        java.util.Objects.requireNonNull(clazz, "Variable class cannot be null");
        if (clazz.getTypeParameters().length > 0) {
            throw new IllegalArgumentException("Generic classes are not supported: " + clazz.getName()
                    + "; wrap the value in a concrete record");
        }
    }

    private static <T> Class<T> getClassOf(T[] array) {
        return (Class<T>) array.getClass().getComponentType();
    }

    /** Creates a type with the value class inferred from the type argument: {@code VariableType.<Foo>of("foo")}. */
    @SafeVarargs
    public static <T> VariableType<T> of(String name, T... reified) {
        return of(name, getClassOf(reified));
    }

    /** Creates a type with a non-generic value class. */
    public static <T> VariableType<T> of(String name, Class<T> clazz) {
        return new VariableType<>(name, clazz);
    }
}
