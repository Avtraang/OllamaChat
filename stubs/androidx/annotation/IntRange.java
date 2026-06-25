package androidx.annotation; public @interface IntRange { long from() default Long.MIN_VALUE; long to() default Long.MAX_VALUE; }
