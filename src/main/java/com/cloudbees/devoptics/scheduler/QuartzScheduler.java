package com.cloudbees.devoptics.scheduler;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.ScheduleBuilder;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.CDI;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:mgagliardo@cloudbees.com">Michael Gagliardo</a>
 */

@ApplicationScoped
public class QuartzScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzScheduler.class);
    private static final String EXECUTION_KEY = "execution";
    private static final String NAME_KEY = "name";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Scheduler scheduler;

    public void init(@Observes @Initialized(ApplicationScoped.class) Object init) {
        List<ScheduledJobConfig> scheduledJobConfigs = new ArrayList<>();

        for (AnnotatedType<?> type : ScheduledExtension.getAnnotatedTypeList()) {
            Map<Method, List<Scheduled>> scheduledMethods = new LinkedHashMap<>();

            for (AnnotatedMethod<?> method : type.getMethods()) {
                if (!method.isAnnotationPresent(Scheduled.class)) {
                    continue;
                }

                List<Scheduled> scheduledList = new ArrayList<>(method.getAnnotations(Scheduled.class));
                method.getJavaMember().setAccessible(true);
                scheduledMethods.put(method.getJavaMember(), scheduledList);
            }

            if (scheduledMethods.size() > 0) {
                Optional<Bean<?>> managedBean = CDI.current().getBeanManager().getBeans(type.getJavaClass()).stream().findFirst();
                if (managedBean.isPresent()) {
                    CreationalContext<?> context = CDI.current().getBeanManager().createCreationalContext(managedBean.get());
                    Object instance = CDI.current().getBeanManager().getReference(managedBean.get(), type.getJavaClass(), context);

                    scheduledMethods.forEach((method, scheduledList) -> {
                        scheduledJobConfigs.add(ScheduledJobConfig.of(
                                method.getDeclaringClass().getName() + "_" + method.getName(),
                                scheduledList,
                                () -> method.invoke(instance)));
                    });
                }
            }
        }

        startup(scheduledJobConfigs);
    }

    private void startup(List<ScheduledJobConfig> scheduledJobConfigs) {
        if (running.compareAndSet(false, true)) {
            try {
                Properties props = new Properties();
                props.put("org.quartz.scheduler.instanceName", "DefaultQuartzScheduler");
                props.put("org.quartz.scheduler.rmi.export", false);
                props.put("org.quartz.scheduler.rmi.proxy", false);
                props.put("org.quartz.scheduler.wrapJobExecutionInUserTransaction", false);
                props.put("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
                props.put("org.quartz.threadPool.threadCount", "10");
                props.put("org.quartz.threadPool.threadPriority", "5");
                props.put("org.quartz.threadPool.threadsInheritContextClassLoaderOfInitializingThread", true);
                props.put("org.quartz.jobStore.misfireThreshold", "60000");
                props.put("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");

                SchedulerFactory schedulerFactory = new StdSchedulerFactory(props);
                scheduler = schedulerFactory.getScheduler();

                scheduler.setJobFactory((bundle, scheduler) -> {
                    Class<? extends Job> jobClass = bundle.getJobDetail().getJobClass();
                    if (jobClass.equals(ScheduledJob.class)) {
                        return new ScheduledJob();
                    }

                    throw new IllegalStateException("Unsupported job class: " + jobClass);
                });

                Config config = ConfigProvider.getConfig();

                for (ScheduledJobConfig scheduledJobConfig : scheduledJobConfigs) {
                    for (int i = 0; i < scheduledJobConfig.scheduled.size(); i++) {
                        Scheduled scheduled = scheduledJobConfig.scheduled.get(i);

                        String name = scheduledJobConfig.name + "_" + (i + 1);
                        JobDataMap jobDataMap = new JobDataMap();
                        jobDataMap.put(NAME_KEY, scheduledJobConfig.name);
                        jobDataMap.put(EXECUTION_KEY, scheduledJobConfig.execution);

                        JobBuilder jobBuilder = JobBuilder.newJob(ScheduledJob.class)
                                .withIdentity(name, Scheduled.class.getName())
                                .setJobData(jobDataMap);

                        ScheduleBuilder<?> scheduleBuilder;

                        String every = scheduled.every();

                        if (!scheduled.property().isEmpty()) {
                            every = config.getValue(scheduled.property(), String.class);
                        }

                        if (every != null) {
                            every = every.trim();
                            if (Character.isDigit(every.charAt(0))) {
                                every = "PT" + every;
                            }

                            Duration duration;
                            try {
                                duration = Duration.parse(every);
                            } catch (Exception e) {
                                throw new IllegalStateException("Invalid every() expression on: " + scheduled, e);
                            }

                            scheduleBuilder = SimpleScheduleBuilder.simpleSchedule()
                                    .withIntervalInMilliseconds(duration.toMillis()).repeatForever();
                        } else {
                            throw new IllegalArgumentException("Invalid schedule configuration: " + scheduled);
                        }

                        TriggerBuilder<?> triggerBuilder = TriggerBuilder.newTrigger()
                                .withIdentity(name + "_trigger", Scheduled.class.getName())
                                .withSchedule(scheduleBuilder);
                        if (scheduled.delay() > 0) {
                            triggerBuilder.startAt(new Date(Instant.now()
                                    .plusMillis(scheduled.delayUnit().toMillis(scheduled.delay())).toEpochMilli()));
                        }
                        scheduler.scheduleJob(jobBuilder.build(), triggerBuilder.build());
                        LOGGER.info("Scheduled business method {} with config {}", scheduledJobConfig.name, scheduled);
                    }
                }

                scheduler.start();
            } catch (SchedulerException e) {
                throw new IllegalStateException("Unable to start Scheduler", e);
            }
        }
    }

    @PreDestroy
    void destroy() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                try {
                    scheduler.shutdown();
                } catch (SchedulerException e) {
                    LOGGER.error("Unable to shutdown scheduler.", e);
                }
            }
        }
    }

    interface Execution {
        void invoke() throws Exception;
    }

    static class ScheduledJobConfig {
        private final String name;
        private final Execution execution;
        private final List<Scheduled> scheduled;

        static ScheduledJobConfig of(String name, List<Scheduled> scheduled, Execution execution) {
            return new ScheduledJobConfig(name, scheduled, execution);
        }

        private ScheduledJobConfig(String name, List<Scheduled> scheduled, Execution execution) {
            this.name = name;
            this.scheduled = scheduled;
            this.execution = execution;
        }
    }

    class ScheduledJob implements Job {

        ScheduledJob() { }

        @Override
        public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
            JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();
            String name = jobDataMap.getString(NAME_KEY);

            try {
                Object execution = jobDataMap.get(EXECUTION_KEY);
                if (execution instanceof Execution) {
                    ((Execution)execution).invoke();
                } else {
                    throw new IllegalStateException("Unregistered execution.");
                }
            } catch (Exception e) {
                throw new JobExecutionException(
                        String.format("Unable to execute scheduled job: %s", name),
                        e
                );
            }
        }
    }
}
