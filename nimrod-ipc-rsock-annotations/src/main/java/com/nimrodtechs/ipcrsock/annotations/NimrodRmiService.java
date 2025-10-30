package com.nimrodtechs.ipcrsock.annotations;

import org.springframework.stereotype.Service;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)   //
@Service                              // meta-annotate Spring's @Service
public @interface NimrodRmiService {
    /**
     * Optional route prefix.
     * For example: @NimrodRmiService(prefix = "pricing")
     * → generated routes become "pricing.methodName".
     */
    String prefix() default "";
}