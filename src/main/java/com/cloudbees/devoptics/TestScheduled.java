package com.cloudbees.devoptics;

import com.cloudbees.devoptics.scheduler.Scheduled;

import javax.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:mgagliardo@cloudbees.com">Michael Gagliardo</a>
 */
@ApplicationScoped
public class TestScheduled {

    @Scheduled(every = "10s", property = "increment.every")
    void increment() {
        System.out.println("Called!");
    }

}
