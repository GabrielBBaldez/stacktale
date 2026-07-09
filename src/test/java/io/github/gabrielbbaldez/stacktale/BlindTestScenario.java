package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * Generates the two artifacts for the blind A/B evaluation described in the README:
 * the SAME run writes (a) a classic console-style log with several concurrent request
 * threads interleaved ({@code target/blind-console.log}) and (b) the stacktale report
 * ({@code target/blind-errors-ai.log}).
 *
 * The scenario: checkout explodes on a total-limit sanity check. The stack trace alone
 * points at the validator; the actual root cause (a stale-price fallback that mixes
 * currencies after the pricing service times out) is only visible in the events that
 * precede the error.
 */
public final class BlindTestScenario {

    public static void main(String[] args) throws Exception {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);

        // (a) classic log, the way most services still log today
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(ctx);
        encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{40} - %msg%n%ex");
        encoder.start();
        FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> classic = new FileAppender<>();
        classic.setContext(ctx);
        classic.setFile("target/blind-console.log");
        classic.setAppend(false);
        classic.setEncoder(encoder);
        classic.start();
        root.addAppender(classic);

        // (b) stacktale
        StacktaleAppender stacktale = new StacktaleAppender();
        stacktale.setContext(ctx);
        stacktale.setFile("target/blind-errors-ai.log");
        stacktale.setAppPackages("com.shopfast");
        stacktale.setInstallUncaughtHandler(false);
        stacktale.start();
        root.addAppender(stacktale);

        CountDownLatch done = new CountDownLatch(6);
        for (int i = 0; i < 6; i++) {
            int id = i;
            new Thread(() -> {
                try {
                    noiseWorker(id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "http-nio-8080-exec-" + (i + 2)).start();
        }

        Thread buggy = new Thread(BlindTestScenario::buggyCheckout, "http-nio-8080-exec-1");
        buggy.start();
        buggy.join();
        done.await();

        classic.stop();
        System.out.println("artifacts: target/blind-console.log + target/blind-errors-ai.log");
    }

    /** Plausible traffic: product browsing, cache hits, health checks, kafka polling. */
    private static void noiseWorker(int id) throws InterruptedException {
        Random rnd = new Random(42L + id);
        org.slf4j.Logger productCtl = LoggerFactory.getLogger("com.shopfast.product.ProductController");
        org.slf4j.Logger cache = LoggerFactory.getLogger("com.shopfast.cache.CatalogCache");
        org.slf4j.Logger health = LoggerFactory.getLogger("com.shopfast.ops.HealthController");
        org.slf4j.Logger kafka = LoggerFactory.getLogger("com.shopfast.events.OrderEventsConsumer");
        org.slf4j.Logger repo = LoggerFactory.getLogger("com.shopfast.product.ProductRepository");

        MDC.put("traceId", "n" + id + "f" + rnd.nextInt(999));
        try {
            for (int i = 0; i < 14; i++) {
                int productId = 1000 + rnd.nextInt(9000);
                switch (i % 5) {
                    case 0 -> productCtl.info("GET /products/{} 200 ({} ms)", productId, 3 + rnd.nextInt(40));
                    case 1 -> cache.info("catalog cache hit for product {}", productId);
                    case 2 -> repo.info("loaded product {} in {} ms", productId, 1 + rnd.nextInt(12));
                    case 3 -> health.info("GET /actuator/health 200");
                    default -> kafka.info("polled {} records from orders-events (lag={})", rnd.nextInt(5), rnd.nextInt(30));
                }
                Thread.sleep(5 + rnd.nextInt(25));
            }
        } finally {
            MDC.clear();
        }
    }

    /** The request that dies — root cause is the stale-price fallback, not the validator. */
    private static void buggyCheckout() {
        org.slf4j.Logger orderCtl = LoggerFactory.getLogger("com.shopfast.order.OrderController");
        org.slf4j.Logger stock = LoggerFactory.getLogger("com.shopfast.stock.StockService");
        org.slf4j.Logger pricing = LoggerFactory.getLogger("com.shopfast.pricing.PricingClient");
        org.slf4j.Logger priceCache = LoggerFactory.getLogger("com.shopfast.pricing.PriceCache");
        org.slf4j.Logger checkout = LoggerFactory.getLogger("com.shopfast.order.CheckoutService");

        MDC.put("traceId", "7c2e");
        MDC.put("userId", "5817");
        try {
            orderCtl.info("POST /orders/889/checkout (3 items, currency=BRL)");
            Thread.sleep(60);
            stock.info("stock reserved for order 889: [sku-4411 x1, sku-2903 x2]");
            Thread.sleep(40);
            pricing.warn("pricing-service timeout after 800ms (attempt 1/2), retrying");
            Thread.sleep(90);
            pricing.warn("pricing-service timeout after 800ms (attempt 2/2), giving up");
            priceCache.warn("FALLBACK: using cached prices for order 889 — cache age 26h, cached currency=USD, order currency=BRL, NO conversion applied");
            Thread.sleep(30);
            checkout.info("computed order total: 15499.00 for order 889");
            try {
                validateTotal(889, 15499.00);
            } catch (Exception e) {
                checkout.error("checkout failed for order {}", 889, e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            MDC.clear();
        }
    }

    private static void validateTotal(int orderId, double total) {
        if (total > 5000.00) {
            throw new IllegalStateException(
                    "order total sanity check failed: 15499.00 exceeds per-order limit 5000.00 (order " + orderId + ")");
        }
    }
}
