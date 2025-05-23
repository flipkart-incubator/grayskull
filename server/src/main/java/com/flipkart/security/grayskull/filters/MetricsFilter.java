package com.flipkart.security.grayskull.filters;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@WebFilter
@Order(Integer.MIN_VALUE + 1)
public class MetricsFilter extends OncePerRequestFilter {

    private static final String METHOD_TAG = "method";
    private static final String STATUS_TAG = "status";
    private static final String URI_TAG = "uri";

    private final MeterRegistry meterRegistry;

    public MetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        String path = request.getServletPath();
        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            stopWatch.stop();
            submitMetrics(request, status, stopWatch.getTotalTimeMillis(), path);
        }
    }


    private void submitMetrics(HttpServletRequest request, int status, long time, String path) {
        String method = request.getMethod();
        String series = getSeries(status);

        Timer.builder("timer.response")
                .tag(METHOD_TAG, method)
                .tag(STATUS_TAG, series)
                .tag(URI_TAG, path)
                .register(meterRegistry)
                .record(time, TimeUnit.MILLISECONDS);

        Timer.builder("timer.response.aggregated")
                .register(meterRegistry)
                .record(time, TimeUnit.MILLISECONDS);
    }

    private String getSeries(int status) {
        return status / 100 + "XX";
    }

}
