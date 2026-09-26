package com.rootopathy.careos.prototype.api;

import com.rootopathy.careos.prototype.application.PrototypeRegistry;
import com.rootopathy.careos.prototype.domain.PrototypeScreen;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PrototypeController {
    private final PrototypeRegistry registry;

    public PrototypeController(PrototypeRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/prototype-screens")
    public List<PrototypeScreen> screens() {
        return registry.all();
    }

    @GetMapping("/system-summary")
    public Map<String, Object> summary() {
        return Map.of(
                "product", "ROOTOPATHY CareOS",
                "edition", "Java Spring Boot + React/Node",
                "generatedAt", Instant.now(),
                "screenCount", registry.all().size(),
                "modules", List.of(
                        "Administration",
                        "Workforce",
                        "Patient registry",
                        "Scheduling",
                        "Encounters",
                        "Clinical"));
    }
}
