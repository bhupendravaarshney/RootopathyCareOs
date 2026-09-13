package com.rootopathy.careos.shared.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
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
                .addFilters(new CorrelationIdFilter())
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
    }

    private record ContractRequest(@NotBlank String name) {}
}
