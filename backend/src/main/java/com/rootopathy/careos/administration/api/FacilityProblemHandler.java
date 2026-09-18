package com.rootopathy.careos.administration.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=FacilityController.class)
public class FacilityProblemHandler {
 @ExceptionHandler(IllegalArgumentException.class)
 ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e,HttpServletRequest request){var p=ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);p.setType(java.net.URI.create("https://careos.local/problems/invalid-facility-request"));p.setTitle("Invalid facility request");p.setDetail(e.getMessage());p.setInstance(java.net.URI.create(request.getRequestURI()));return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
 @ExceptionHandler(com.rootopathy.careos.administration.application.FacilityException.class)
 ResponseEntity<ProblemDetail> facility(com.rootopathy.careos.administration.application.FacilityException e,HttpServletRequest request){var status=switch(e.reason()){case PRECONDITION_REQUIRED->HttpStatus.PRECONDITION_REQUIRED;case STALE->HttpStatus.PRECONDITION_FAILED;case NOT_FOUND->HttpStatus.NOT_FOUND;case CONFLICT->HttpStatus.CONFLICT;default->HttpStatus.BAD_REQUEST;};var p=ProblemDetail.forStatus(status);p.setType(java.net.URI.create("https://careos.local/problems/facility-"+e.reason().name().toLowerCase().replace('_','-')));p.setTitle("Facility request failed");p.setDetail(e.getMessage());p.setInstance(java.net.URI.create(request.getRequestURI()));return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
}
