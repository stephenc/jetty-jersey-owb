package com.cloudbees.devoptics;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GreeterImpl implements Greeter {

    @Inject
    @ConfigProperty(name = "greetee.name", defaultValue = "World")
    String name;

    @Override
    public String getMessage() {
        return String.format("Hello %s", name);
    }
}
