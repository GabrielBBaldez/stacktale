package io.github.gabrielbbaldez.stacktale.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The reactive twin of {@link StacktaleRequestFilter}: opens each request's story with
 * its HTTP line and plants a {@code traceId} in the <em>Reactor Context</em>. With
 * automatic context propagation enabled (the auto-configuration turns it on when
 * micrometer's context-propagation is present), that traceId is restored into the MDC on
 * every operator — so logs emitted deep inside {@code flatMap}s and scheduler hops still
 * correlate into one story.
 */
public final class StacktaleWebFluxFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(StacktaleRequestFilter.REQUEST_LOGGER);

    static final String TRACE_KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        // opening the story is best-effort enrichment — MDC.put throws when no MDCAdapter
        // is bound; a broken/partial SLF4J binding must never fail the actual HTTP request
        try {
            String line = exchange.getRequest().getMethod() + " " + exchange.getRequest().getURI().getRawPath();
            String previous = MDC.get(TRACE_KEY);
            MDC.put(TRACE_KEY, traceId);
            try {
                log.info("{}", line);
            } finally {
                if (previous != null) MDC.put(TRACE_KEY, previous);
                else MDC.remove(TRACE_KEY);
            }
        } catch (Throwable t) {
            // fall through: the request continues, only the story-opening line is skipped
        }

        String finalTraceId = traceId;
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(TRACE_KEY, finalTraceId));
    }
}
