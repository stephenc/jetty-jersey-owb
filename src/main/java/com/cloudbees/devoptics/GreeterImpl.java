package com.cloudbees.devoptics;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreeterImpl implements Greeter {

    @Override
    public String getMessage() {
        return "Hello World";
    }
}
