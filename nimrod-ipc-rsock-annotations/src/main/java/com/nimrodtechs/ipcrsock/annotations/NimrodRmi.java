package com.nimrodtechs.ipcrsock.annotations;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE) // compile-time only
public @interface NimrodRmi {
    /** Route name; defaults to method name if empty. */
    String route() default "";
    /** Optional doc string. */
    String description() default "";
    /** Optional version tag. */
    String version() default "1.0";
}