package com.rootopathy.careos.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvFormulaGuardTest {
    @Test
    void neutralizesFormulaPrefixesIncludingLeadingWhitespace() {
        assertThat(CsvFormulaGuard.safeCell("=2+3")).isEqualTo("'=2+3");
        assertThat(CsvFormulaGuard.safeCell("  @SUM(A1:A2)")).isEqualTo("'  @SUM(A1:A2)");
        assertThat(CsvFormulaGuard.safeCell("-10+20")).isEqualTo("'-10+20");
        assertThat(CsvFormulaGuard.safeCell("Aggregate report")).isEqualTo("Aggregate report");
    }

    @Test
    void quotesCsvAfterNeutralization() {
        assertThat(CsvFormulaGuard.quotedCell("=cmd|'/C calc'!A0"))
                .isEqualTo("\"'=cmd|'/C calc'!A0\"");
        assertThat(CsvFormulaGuard.quotedCell("a\"b")).isEqualTo("\"a\"\"b\"");
    }
}
