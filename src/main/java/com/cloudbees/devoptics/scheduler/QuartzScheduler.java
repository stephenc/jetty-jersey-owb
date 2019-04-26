package com.cloudbees.devoptics.scheduler;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.ScheduleBuilder;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
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
import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A scheduler used to run methods annotated with {@link Scheduled} based on the configuration outlined in each
 * annotation.
 *
 * @author <a href="mailto:mgagliardo@cloudbees.com">Michael Gagliardo</a>
 */

@ApplicationScoped
public class QuartzScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzScheduler.class);

    /**
     * Key used to store/retrieve the method to invoke
     */
    private static final String EXECUTION_KEY = "execution";

    /**
     * Key used to indicate the name of the method to invoke
     */
    private static final String NAME_KEY = "name";

    @Inject
    @ConfigProperty(name = "scheduler.instanceName", defaultValue = "DefaultQuartzScheduler")
    private String schedulerName;

    @Inject
    @ConfigProperty(name = "scheduler.rmi.export", defaultValue = "false")
    private boolean schedulerRmiExport;

    @Inject
    @ConfigProperty(name = "scheduler.rmi.proxy", defaultValue = "false")
    private boolean schedulerRmiProxy;

    @Inject
    @ConfigProperty(name = "scheduler.wrapJobExecutionInUserTransaction", defaultValue = "false")
    private boolean schedulerWrapJobExecutionInUserTransaction;

    @Inject
    @ConfigProperty(name = "scheduler.threadPool.class", defaultValue = "org.quartz.simpl.SimpleThreadPool")
    private String schedulerThreadPoolClass;

    @Inject
    @ConfigProperty(name = "scheduler.threadPool.threadCount", defaultValue = "10")
    private String schedulerThreadPoolThreadCount;

    @Inject
    @ConfigProperty(name = "scheduler.threadPool.threadPriority", defaultValue = "5")
    private String schedulerThreadPoolThreadPriority;

    @Inject
    @ConfigProperty(name = "scheduler.threadsInheritContextClassLoaderOfInitializingThread", defaultValue = "true")
    private boolean schedulerThreadsInheritContextClassLoaderOfInitializingThread;

    @Inject
    @ConfigProperty(name = "scheduler.jobStore.misfireThreshold", defaultValue = "60000")
    private String schedulerJobStoreMisfireThreshold;

    @Inject
    @ConfigProperty(name = "scheduler.jobStore.class", defaultValue = "org.quartz.simpl.RAMJobStore")
    private String schedulerJobStoreClass;

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

                if (!validateScheduledMethod(method.getJavaMember())) {
                    throw new IllegalArgumentException(String.format("Scheduled methods must accept zero arguments and return void. %s", method));
                }

                List<Scheduled> scheduledList = new ArrayList<>(method.getAnnotations(Scheduled.class));
                method.getJavaMember().setAccessible(true);
                scheduledMethods.put(method.getJavaMember(), scheduledList);
            }

            if (scheduledMethods.size() > 0) {
                CDI.current().getBeanManager().getBeans(type.getJavaClass())
                    .stream()
                    .findFirst()
                    .ifPresent(managedBean -> {
                        CreationalContext<?> context = CDI.current().getBeanManager().createCreationalContext(managedBean);
                        Object instance = CDI.current().getBeanManager().getReference(managedBean, type.getJavaClass(), context);
                        scheduledMethods.entrySet()
                            .stream()
                            .map(methodListEntry -> ScheduledJobConfig.of(
                                methodListEntry.getKey().getDeclaringClass().getName() + "_" + methodListEntry.getKey().getName(),
                                methodListEntry.getValue(),
                                () -> methodListEntry.getKey().invoke(instance))
                            ).forEach(scheduledJobConfigs::add);
                    });
            }
        }

        startup(scheduledJobConfigs);
    }

    private boolean validateScheduledMethod(Method method) {
        return method.getParameterCount() == 0 && method.getReturnType().equals(Void.TYPE);
    }

    private void startup(List<ScheduledJobConfig> scheduledJobConfigs) {
        if (running.compareAndSet(false, true)) {
            try {
                scheduler = new StdSchedulerFactory(getSchedulerProperties()).getScheduler();
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

                        if (StringUtil.isNotBlank(scheduled.property())) {
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

    private Properties getSchedulerProperties() {
        Properties props = new Properties();
        props.put("org.quartz.scheduler.instanceName", schedulerName);
        props.put("org.quartz.scheduler.rmi.export", schedulerRmiExport);
        props.put("org.quartz.scheduler.rmi.proxy", schedulerRmiProxy);
        props.put("org.quartz.scheduler.wrapJobExecutionInUserTransaction", schedulerWrapJobExecutionInUserTransaction);
        props.put("org.quartz.threadPool.class", schedulerThreadPoolClass);
        props.put("org.quartz.threadPool.threadCount", schedulerThreadPoolThreadCount);
        props.put("org.quartz.threadPool.threadPriority", schedulerThreadPoolThreadPriority);
        props.put("org.quartz.threadPool.threadsInheritContextClassLoaderOfInitializingThread", schedulerThreadsInheritContextClassLoaderOfInitializingThread);
        props.put("org.quartz.jobStore.misfireThreshold", schedulerJobStoreMisfireThreshold);
        props.put("org.quartz.jobStore.class", schedulerJobStoreClass);

        return props;
    }

    /**
     * An interface to define the invocation of a scheduled task
     */
    interface Execution {
        void invoke() throws Exception;
    }

    /**
     * Holds a execution and its associated scheduled annotations
     */
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

    /**
     * The job which will be executed by the QuartzScheduler. The execute method will attempt to
     * invoke the annotated execution
     */
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
