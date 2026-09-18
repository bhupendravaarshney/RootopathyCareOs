package com.rootopathy.careos.administration.domain;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public record InternationalSettingsFormatPreview(
        String sampleDate,
        String sampleTime,
        String sampleNumber,
        String sampleCurrency,
        boolean localeLibraryDerived) {
    private static final Instant SAMPLE_INSTANT = Instant.parse("2030-06-15T13:45:00Z");

    public InternationalSettingsFormatPreview {
        sampleDate = Objects.requireNonNull(sampleDate, "sampleDate");
        sampleTime = Objects.requireNonNull(sampleTime, "sampleTime");
        sampleNumber = Objects.requireNonNull(sampleNumber, "sampleNumber");
        sampleCurrency = Objects.requireNonNull(sampleCurrency, "sampleCurrency");
        if (!localeLibraryDerived) {
            throw new IllegalArgumentException("format previews must be locale-library derived");
        }
    }

    public static InternationalSettingsFormatPreview create(
            String localeTag, String timezone, String currencyCode) {
        var locale = Locale.forLanguageTag(localeTag);
        var zone = ZoneId.of(timezone);
        var dateTime = SAMPLE_INSTANT.atZone(zone);
        var date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(dateTime);
        var time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .format(dateTime);
        var numberFormatter = NumberFormat.getNumberInstance(locale);
        numberFormatter.setMinimumFractionDigits(2);
        numberFormatter.setMaximumFractionDigits(2);
        var currencyFormatter = NumberFormat.getCurrencyInstance(locale);
        currencyFormatter.setCurrency(Currency.getInstance(currencyCode));
        return new InternationalSettingsFormatPreview(
                date,
                time,
                numberFormatter.format(1_234_567.89d),
                currencyFormatter.format(1_234.56d),
                true);
    }
}
