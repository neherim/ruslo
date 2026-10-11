package io.github.neherim.ruslo.core.api.definition;

/**
 * Named, typed message an await can accept.
 *
 * @param name  message name
 * @param clazz payload class
 */
public record MessageType<T>(String name, Class<T> clazz) {
    /**
     * @throws IllegalArgumentException if the name is blank or the class is generic
     */
    public MessageType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Message name cannot be blank");
        }
        java.util.Objects.requireNonNull(clazz, "Payload class cannot be null");
        if (clazz.getTypeParameters().length > 0) {
            throw new IllegalArgumentException("Generic classes are not supported: " + clazz.getName()
                    + "; wrap the value in a concrete record");
        }
    }

    private static <T> Class<T> getClassOf(T[] array) {
        return (Class<T>) array.getClass().getComponentType();
    }

    /** Creates a type with the payload class inferred from the type argument: {@code MessageType.<Foo>of("foo")}. */
    @SafeVarargs
    public static <T> MessageType<T> of(String name, T... reified) {
        return of(name, getClassOf(reified));
    }

    /** Creates a type with a non-generic payload class. */
    public static <T> MessageType<T> of(String name, Class<T> clazz) {
        return new MessageType<>(name, clazz);
    }
}
