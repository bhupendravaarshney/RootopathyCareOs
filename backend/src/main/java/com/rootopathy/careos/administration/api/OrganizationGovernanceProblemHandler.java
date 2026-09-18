package com.rootopathy.careos.administration.api;

import com.rootopathy.careos.administration.application.OrganizationGovernanceException;
import com.rootopathy.careos.governance.application.IdempotencyException;
import com.rootopathy.careos.shared.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=OrganizationGovernanceController.class)
public class OrganizationGovernanceProblemHandler {
    @ExceptionHandler(OrganizationGovernanceException.class)
    ResponseEntity<ProblemDetail> handle(OrganizationGovernanceException e,HttpServletRequest request){
        var status=switch(e.reason()){case INVALID_REQUEST->HttpStatus.BAD_REQUEST;case NOT_FOUND->HttpStatus.NOT_FOUND;case PRECONDITION_REQUIRED->HttpStatus.PRECONDITION_REQUIRED;case STALE_REVISION->HttpStatus.PRECONDITION_FAILED;case CONFLICT->HttpStatus.CONFLICT;};
        var code=switch(e.reason()){case INVALID_REQUEST->"governance-invalid";case NOT_FOUND->"governance-not-found";case PRECONDITION_REQUIRED->"governance-precondition-required";case STALE_REVISION->"governance-stale-revision";case CONFLICT->"governance-conflict";};
        var p=ProblemDetail.forStatusAndDetail(status,e.getMessage());p.setType(URI.create("https://careos.example/problems/"+code));p.setTitle(status.getReasonPhrase());p.setInstance(URI.create(request.getRequestURI()));p.setProperty("code",code);p.setProperty("correlationId",CorrelationIdFilter.from(request));
        if(e.field()!=null)p.setProperty("errors",List.of(new FieldViolation("/"+e.field(),"m1.field.invalid",e.getMessage())));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
    @ExceptionHandler(IdempotencyException.class)
    ResponseEntity<ProblemDetail> idempotency(IdempotencyException e,HttpServletRequest request){var p=ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,e.getMessage());p.setType(URI.create("https://careos.example/problems/idempotency-key-reused"));p.setTitle("Idempotency conflict");p.setInstance(URI.create(request.getRequestURI()));p.setProperty("code",e.reason()==IdempotencyException.Reason.KEY_REUSED?"idempotency-key-reused":"request-in-progress");p.setProperty("correlationId",CorrelationIdFilter.from(request));return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
}
