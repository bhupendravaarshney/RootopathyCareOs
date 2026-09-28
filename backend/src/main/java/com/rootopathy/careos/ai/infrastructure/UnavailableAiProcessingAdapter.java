package com.rootopathy.careos.ai.infrastructure;

import com.rootopathy.careos.ai.application.AiException;
import com.rootopathy.careos.ai.application.AiProcessingPort;
import org.springframework.stereotype.Component;

/** Default production-safe adapter: no provider is contacted until separately configured. */
@Component
public final class UnavailableAiProcessingAdapter implements AiProcessingPort {
    @Override
    public Result process(Request request) {
        throw new AiException(
                AiException.Reason.DEPENDENCY_UNAVAILABLE,
                "The AI processing provider is not configured for this deployment.");
    }
}
