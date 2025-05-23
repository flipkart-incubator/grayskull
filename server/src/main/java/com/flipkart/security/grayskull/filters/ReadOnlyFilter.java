package com.flipkart.security.grayskull.filters;


import com.flipkart.security.grayskull.configuration.properties.ReadOnlyAppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ReadOnlyFilter extends OncePerRequestFilter {

    private final boolean readOnly;
    private static final Pattern EXEMPTED_PATTERN = Pattern.compile("");

    private static final List<String> BLOCKED_METHODS = Stream.of(
            HttpMethod.PUT,
            HttpMethod.POST,
            HttpMethod.PATCH,
            HttpMethod.DELETE
    ).map(HttpMethod::name).toList();

    public ReadOnlyFilter(ReadOnlyAppProperties readOnlyAppProperties) {
        this.readOnly = readOnlyAppProperties.isEnabled();
    }
    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain) throws ServletException, IOException {
        String method = httpServletRequest.getMethod();
        String servletPath = httpServletRequest.getServletPath();
        if (readOnly && BLOCKED_METHODS.contains(method) && !EXEMPTED_PATTERN.matcher(servletPath).matches()) {
            httpServletResponse.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "The application server is read only. The specified operation is not allowed.");
        } else {
            filterChain.doFilter(httpServletRequest, httpServletResponse);
        }
    }
}
