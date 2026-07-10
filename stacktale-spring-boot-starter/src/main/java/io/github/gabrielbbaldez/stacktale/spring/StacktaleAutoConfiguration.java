package io.github.gabrielbbaldez.stacktale.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.gabrielbbaldez.stacktale.StacktaleAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Zero-config stacktale for Spring Boot: registers the appender on Logback's root logger
 * at startup — no logback.xml editing — with {@code appPackages} defaulting to the
 * {@code @SpringBootApplication} package. The {@code stacktale.request} logger is wired
 * exclusively to the stacktale appender (additivity off), so HTTP request lines feed the
 * story without ever touching the human console.
 */
@AutoConfiguration
@ConditionalOnClass(LoggerContext.class)
@ConditionalOnProperty(prefix = "stacktale", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StacktaleProperties.class)
public class StacktaleAutoConfiguration {

    /** A user-configured appender in logback.xml under this name is respected, never replaced. */
    static final String APPENDER_NAME = "STACKTALE";
    /** The auto-configured appender's own name — replaced on every context refresh. */
    static final String AUTO_APPENDER_NAME = "STACKTALE_AUTO";

    @Bean(destroyMethod = "")
    public StacktaleAppender stacktaleAppender(StacktaleProperties props, BeanFactory beanFactory) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            // a different SLF4J backend is bound; nothing we can do — stay a no-op
            LoggerFactory.getLogger("stacktale")
                    .warn("stacktale starter is on the classpath but Logback is not the SLF4J backend; doing nothing");
            return new StacktaleAppender();
        }
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) instanceof StacktaleAppender existing) {
            return existing; // user already configured one in logback.xml — don't double up
        }
        // Logback's context outlives Spring's: a previous application context (test suite,
        // DevTools restart) may have left OUR appender behind with stale configuration.
        // Replace it — never reuse it — so the current context's properties always apply.
        ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> stale =
                root.getAppender(AUTO_APPENDER_NAME);
        if (stale != null) {
            root.detachAppender(stale);
            stale.stop();
        }

        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setName(AUTO_APPENDER_NAME);
        appender.setFile(props.getFile());
        appender.setAppPackages(resolveAppPackages(props, beanFactory));
        appender.setStorySize(props.getStorySize());
        appender.setStoryWindowSeconds(props.getStoryWindowSeconds());
        appender.setDedupWindowSeconds(props.getDedupWindowSeconds());
        appender.setMaxFileSizeMb(props.getMaxFileSizeMb());
        appender.setMaxBackups(props.getMaxBackups());
        appender.setTruncateOnStart(props.isTruncateOnStart());
        appender.setInstallUncaughtHandler(props.isInstallUncaughtHandler());
        appender.setReportErrorsWithoutThrowable(props.isReportErrorsWithoutThrowable());
        appender.setCaptureExceptionFields(props.isCaptureExceptionFields());
        appender.setRedactionEnabled(props.isRedactionEnabled());
        props.getRedactPatterns().forEach(appender::addRedactPattern);
        appender.setCorrelationMdcKeys(props.getCorrelationMdcKeys());
        appender.setZone(props.getZone());
        appender.setEchoSuppressionMillis(props.getEchoSuppressionMillis());
        props.getContainerLoggers().forEach(appender::addContainerLogger);
        appender.setEmitReportsToLogger(props.isEmitReportsToLogger());
        appender.setMaxReportsPerMinute(props.getMaxReportsPerMinute());
        appender.start();
        root.addAppender(appender);

        // request lines go ONLY to stacktale, never to the user's console appenders
        Logger requestLogger = ctx.getLogger(StacktaleRequestFilter.REQUEST_LOGGER);
        requestLogger.setAdditive(false);
        requestLogger.addAppender(appender);
        return appender;
    }

    /** Detaches the auto-configured appender when the Spring context closes. */
    @Bean
    public org.springframework.beans.factory.DisposableBean stacktaleCleanup(StacktaleAppender appender) {
        return () -> {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx
                    && AUTO_APPENDER_NAME.equals(appender.getName())) {
                ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
                ctx.getLogger(StacktaleRequestFilter.REQUEST_LOGGER).detachAppender(appender);
                appender.stop();
            }
        };
    }

    private String resolveAppPackages(StacktaleProperties props, BeanFactory beanFactory) {
        if (!props.getAppPackages().isBlank()) return props.getAppPackages();
        try {
            if (AutoConfigurationPackages.has(beanFactory)) {
                return String.join(",", AutoConfigurationPackages.get(beanFactory));
            }
        } catch (RuntimeException ignored) {
            // fall through to heuristic mode
        }
        return "";
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "stacktale", name = "request-logging", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<StacktaleRequestFilter> stacktaleRequestFilter(StacktaleAppender appender) {
        FilterRegistrationBean<StacktaleRequestFilter> registration =
                new FilterRegistrationBean<>(new StacktaleRequestFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); // early: open the story before the app runs
        return registration;
    }
}
