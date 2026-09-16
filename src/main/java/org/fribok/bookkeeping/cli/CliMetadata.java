package org.fribok.bookkeeping.cli;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Application-specific declarations; only fields on the selected command path are bound. */
final class CliMetadata {
    static final String NO_DEFAULT = "\u0000";

    private CliMetadata() {
    }

    enum ScopeType { LOCAL, INHERIT }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Command {
        String name();
        String[] description() default {};
        Class<?>[] subcommands() default {};
        boolean mixinStandardHelpOptions() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Option {
        String[] names();
        String[] description() default {};
        boolean required() default false;
        boolean hidden() default false;
        String defaultValue() default NO_DEFAULT;
        ScopeType scope() default ScopeType.LOCAL;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Parameters {
        String index() default "0";
        String[] description() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface ParentCommand {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Spec {
    }
}
