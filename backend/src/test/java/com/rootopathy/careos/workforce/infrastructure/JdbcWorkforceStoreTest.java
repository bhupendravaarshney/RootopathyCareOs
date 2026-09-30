package com.rootopathy.careos.workforce.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcWorkforceStoreTest {
    @Test
    void createsDistinctMemberNumbersForTimeOrderedIdsWithTheSameLeadingCharacters() {
        var first = UUID.fromString("019d9fd0-0000-7000-8000-000000000001");
        var second = UUID.fromString("019d9fd0-0000-7000-8000-000000000002");

        assertThat(JdbcWorkforceStore.memberNumber(first))
                .isEqualTo("WF-019D9FD0-0000-7000-8000-000000000001")
                .hasSizeLessThanOrEqualTo(48)
                .matches("[A-Z0-9][A-Z0-9_-]{2,47}");
        assertThat(JdbcWorkforceStore.memberNumber(second))
                .isNotEqualTo(JdbcWorkforceStore.memberNumber(first));
    }
}
