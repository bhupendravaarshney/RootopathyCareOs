package com.rootopathy.careos.reporting.application;

/** Neutralizes untrusted spreadsheet cells before ordinary CSV quoting. */
public final class CsvFormulaGuard {
    private CsvFormulaGuard() {}

    public static String safeCell(String supplied) {
        var value = supplied == null ? "" : supplied;
        var index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        if (index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    public static String quotedCell(String supplied) {
        return '"' + safeCell(supplied).replace("\"", "\"\"") + '"';
    }
}
