package com.nimrodtechs.ipcrsock.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class NimrodClientEnabledCondition implements Condition {

    private static final String PREFIX = "nimrod.rsock.client-side.";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {

        Environment env = context.getEnvironment();

        // Fast path: if no property sources, no client config
        if (env == null) {
            return false;
        }

        for (PropertySource<?> ps : ((org.springframework.core.env.AbstractEnvironment) env).getPropertySources()) {

            Object source = ps.getSource();

            // We only care about map-based property sources (YAML/properties end up here)
            if (source instanceof java.util.Map<?, ?> map) {

                for (Object keyObj : map.keySet()) {

                    if (!(keyObj instanceof String key)) {
                        continue;
                    }

                    //Core logic
                    if (key.startsWith(PREFIX) && key.endsWith(".host")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
