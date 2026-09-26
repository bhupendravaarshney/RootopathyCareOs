package com.rootopathy.careos.patientregistry.application;

import java.util.UUID;

public interface PatientSensitiveValueCodec {
    byte[] encrypt(UUID organizationId, String field, String normalizedValue);

    String digest(UUID organizationId, String field, String normalizedValue);
}
