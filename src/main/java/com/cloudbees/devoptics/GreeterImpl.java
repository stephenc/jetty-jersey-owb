package com.cloudbees.devoptics;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.annotation.Timed;

@ApplicationScoped
public class GreeterImpl implements Greeter {

    @Inject
    @ConfigProperty(name = "greetee.name", defaultValue = "World")
    String name;

    @Override
    @Timed(name="test")
    public String getMessage() {
        return String.format("Hello %s", name);
    }
}
