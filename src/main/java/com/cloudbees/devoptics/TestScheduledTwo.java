package com.cloudbees.devoptics;

import com.cloudbees.devoptics.scheduler.Scheduled;

/**
 * @author <a href="mailto:mgagliardo@cloudbees.com">Michael Gagliardo</a>
 */
public class TestScheduledTwo {

    @Scheduled(every = "10s")
    @Scheduled(every = "15s")
    void run() {
        System.out.println("Called 2!");
    }
}
