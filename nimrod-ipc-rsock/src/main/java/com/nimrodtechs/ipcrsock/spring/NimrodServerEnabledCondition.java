package com.nimrodtechs.ipcrsock.spring;

import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class NimrodServerEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();

        Integer port = env.getProperty("nimrod.rsock.server.port", Integer.class);

        return port != null && port > 0;
    }
}
