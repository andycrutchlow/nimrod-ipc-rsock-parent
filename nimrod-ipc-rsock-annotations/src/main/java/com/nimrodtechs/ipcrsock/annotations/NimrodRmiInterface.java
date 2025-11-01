package com.nimrodtechs.ipcrsock.annotations;


import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface NimrodRmiInterface {
    /**
     * Logical service name used for routing, e.g. "datamanager".
     * Routes become "<serviceName>.<methodName>".
     */
    String serviceName();
}