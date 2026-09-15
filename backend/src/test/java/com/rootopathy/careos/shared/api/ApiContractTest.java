package com.rootopathy.careos.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiContractTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ContractController())
                .setControllerAdvice(new ApiProblemHandler())
                .addFilters(new CorrelationIdFilter(), new RequestTelemetryFilter())
                .build();
    }

    @Test
    void preservesAValidClientCorrelationId() throws Exception {
        mockMvc.perform(get("/contract/ok").header(CorrelationIdFilter.HEADER_NAME, "client-request-42"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "client-request-42"));
    }

    @Test
    void replacesAnUnsafeCorrelationId() throws Exception {
        mockMvc.perform(get("/contract/ok").header(CorrelationIdFilter.HEADER_NAME, "unsafe header value"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        CorrelationIdFilter.HEADER_NAME,
                        matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    void returnsRfc9457ValidationDetailsWithTheSameCorrelationId() throws Exception {
        mockMvc.perform(post("/contract/validate")
                        .header(CorrelationIdFilter.HEADER_NAME, "validation-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "validation-42"))
                .andExpect(jsonPath("$.type").value("https://careos.example/problems/request-validation"))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("request-validation"))
                .andExpect(jsonPath("$.correlationId").value("validation-42"))
                .andExpect(jsonPath("$.errors[0].path").value("/name"));
    }

    @Test
    void keepsKnownErrorsSafeAndMachineReadable() throws Exception {
        mockMvc.perform(get("/contract/problem").header(CorrelationIdFilter.HEADER_NAME, "known-42"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("state-conflict"))
                .andExpect(jsonPath("$.detail").value("The resource is not in the required state."))
                .andExpect(jsonPath("$.correlationId").value("known-42"));
    }

    @Test
    void logsOnlyTemplatedRequestMetadataAndRestoresTheOuterCorrelationContext() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(RequestTelemetryFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer-operation-7");
        try {
            mockMvc.perform(get("/contract/patients/{patientId}", "patient-secret-42")
                            .queryParam("token", "query-secret-42")
                            .header(CorrelationIdFilter.HEADER_NAME, "telemetry-42"))
                    .andExpect(status().isOk());

            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            var fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));

            assertThat(event.getFormattedMessage()).isEqualTo("HTTP request completed");
            assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationIdFilter.MDC_KEY, "telemetry-42");
            assertThat(fields)
                    .containsEntry("eventType", "http.request.completed")
                    .containsEntry("httpMethod", "GET")
                    .containsEntry("httpRoute", "/contract/patients/{patientId}")
                    .containsEntry("httpStatus", 200)
                    .containsEntry("outcome", "completed")
                    .containsKey("durationMs");
            assertThat(fields.toString()).doesNotContain("patient-secret-42", "query-secret-42");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer-operation-7");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @RestController
    @RequestMapping("/contract")
    private static final class ContractController {
        @GetMapping("/ok")
        Map<String, String> ok() {
            return Map.of("status", "ok");
        }

        @PostMapping("/validate")
        Map<String, String> validate(@Valid @RequestBody ContractRequest request) {
            return Map.of("name", request.name());
        }

        @GetMapping("/problem")
        void problem() {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "state-conflict",
                    "State conflict",
                    "The resource is not in the required state.");
        }

        @GetMapping("/patients/{patientId}")
        Map<String, String> patient(@PathVariable String patientId) {
            return Map.of("id", patientId);
        }
    }

    private record ContractRequest(@NotBlank String name) {}
}
