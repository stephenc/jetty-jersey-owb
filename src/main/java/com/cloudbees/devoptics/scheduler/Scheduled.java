/*
 * Copyright 2018 CloudBees, Inc.
 * All rights reserved.
 */

package com.cloudbees.devoptics.scheduler;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.cloudbees.devoptics.scheduler.Scheduled.Schedules;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Schedules.class)
public @interface Scheduled {

    /**
     * The property name / environment variable that overrides the default interval.
     *
     * @return property name / environment variable that overrides the default interval or an empty string to disable
     *         property overrides.
     */
    String property() default "";

    /**
     * Defines a period between invocations.
     * <p>
     * The value is parsed with {@link Duration#parse(CharSequence)}. However, if an expression starts with a digit, "PT" prefix
     * is added automatically, so for
     * example, {@code 15m} can be used instead of {@code PT15M} and is parsed as "15 minutes". Note that the absolute value of
     * the value is always used.
     * <p>
     *
     * @return the period expression based on the ISO-8601 duration format {@code PnDTnHnMn.nS}
     */
    String every() default "";

    /**
     * Delays the time the trigger should start at. By default, the trigger starts when registered.
     *
     * @return the initial delay
     */
    long delay() default 0;

    /**
     *
     * @return the unit of initial delay
     */
    TimeUnit delayUnit() default TimeUnit.MINUTES;

    @Retention(RUNTIME)
    @Target(METHOD)
    @interface Schedules {

        Scheduled[] value();

    }
}