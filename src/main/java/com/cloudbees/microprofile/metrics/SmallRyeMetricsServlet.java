package com.cloudbees.microprofile.metrics;

import io.smallrye.metrics.MetricsRequestHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.stream.Stream;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/metrics")
@ApplicationScoped
public class SmallRyeMetricsServlet extends HttpServlet {

    @Inject
    MetricsRequestHandler metricsHandler;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String requestPath = req.getRequestURI();
        String method = req.getMethod();
        Stream<String> acceptHeaders = Collections.list(req.getHeaders("Accept")).stream();

        metricsHandler.handleRequest(requestPath, method, acceptHeaders, (status, message, headers) -> {
            headers.forEach(resp::addHeader);
            resp.setStatus(status);
            resp.getWriter().write(message);
        });
    }
}
