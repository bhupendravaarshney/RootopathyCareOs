package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.ServiceLocationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = ServiceLocationController.class)
public class ServiceLocationProblemHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ServiceLocationException.class)
    ResponseEntity<ProblemDetail> handle(ServiceLocationException exception, HttpServletRequest request) {
        var status = switch (exception.reason()) {
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case STALE -> HttpStatus.PRECONDITION_FAILED;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle("Service location request failed");
        problem.setType(URI.create("https://careos.local/problems/service-location-"
                + exception.reason().name().toLowerCase().replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
