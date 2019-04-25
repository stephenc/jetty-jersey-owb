package com.cloudbees.devoptics.scheduler;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.WithAnnotations;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * @author <a href="mailto:mgagliardo@cloudbees.com">Michael Gagliardo</a>
 */
public class ScheduledExtension implements Extension {

    private static Set<AnnotatedType<?>> annotatedTypeList = new HashSet<>();

    <T> void processAnnotatedType(@Observes @WithAnnotations({Scheduled.class}) ProcessAnnotatedType<T> pat) {
        annotatedTypeList.add(pat.getAnnotatedType());
    }

    static Set<AnnotatedType<?>> getAnnotatedTypeList() {
        return Collections.unmodifiableSet(annotatedTypeList);
    }
}
