package io.github.gabrielbbaldez.stacktale;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runnable demo: simulates an order flow that dies on an NPE and prints where the AI
 * error report landed. Run it from the repo root:
 *
 * <pre>
 * mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" io.github.gabrielbbaldez.stacktale.DemoApp
 * # (on Linux/macOS use ':' instead of ';' as the -cp separator)
 * </pre>
 *
 * The report lands in {@code target/demo-errors-ai.log}.
 */
public final class DemoApp {

    record Customer(String email) {}

    /** A domain exception carrying state — stacktale reads it into the fields: section. */
    static class OrderConfirmationException extends RuntimeException {
        private final int orderId;

        OrderConfirmationException(int orderId, Throwable cause) {
            super("confirmation aborted for order " + orderId, cause);
            this.orderId = orderId;
        }

        public int getOrderId() { return orderId; }
        public boolean isRetryable() { return false; }
        public String getFailedStep() { return "send-confirmation-email"; }
    }

    public static void main(String[] args) throws Exception {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        StacktaleAppender stacktale = new StacktaleAppender();
        stacktale.setContext(ctx);
        stacktale.setFile("target/demo-errors-ai.log");
        stacktale.setAppPackages("io.github.gabrielbbaldez.stacktale");
        stacktale.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(stacktale);

        org.slf4j.Logger controller = LoggerFactory.getLogger("io.github.gabrielbbaldez.stacktale.demo.OrderController");
        org.slf4j.Logger client = LoggerFactory.getLogger("io.github.gabrielbbaldez.stacktale.demo.CustomerClient");
        org.slf4j.Logger cache = LoggerFactory.getLogger("io.github.gabrielbbaldez.stacktale.demo.CustomerCache");
        org.slf4j.Logger service = LoggerFactory.getLogger("io.github.gabrielbbaldez.stacktale.demo.OrderService");

        MDC.put("traceId", "9f3a");
        MDC.put("userId", "42");
        try {
            controller.info("POST /orders/123/confirm");
            Thread.sleep(100);
            client.info("fetching customer 555 → HTTP 404");
            cache.warn("miss for customer 555, returning null");
            Thread.sleep(300);
            try {
                confirmOrder(123, null);
            } catch (Exception e) {
                service.error("Failed to confirm order {}", 123, e);
            }
        } finally {
            MDC.clear();
        }
    }

    private static void confirmOrder(int orderId, Customer customer) {
        try {
            // the demo bug: nobody checked the cache miss
            String email = customer.email();
            System.out.println("confirmation sent to " + email);
        } catch (NullPointerException e) {
            throw new OrderConfirmationException(orderId, e);
        }
    }
}
