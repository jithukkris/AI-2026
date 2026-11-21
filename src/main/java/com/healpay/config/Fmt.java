package com.healpay.config;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formatting helper used from Thymeleaf templates as {@code ${@fmt.money(x)}}.
 */
@Component("fmt")
public class Fmt {

    private final DateTimeFormatter timeFormat;
    private final DateTimeFormatter shortTimeFormat;

    public Fmt(AppProperties props) {
        ZoneId zone = ZoneId.of(props.displayZone() == null ? "UTC" : props.displayZone());
        this.timeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss").withZone(zone);
        this.shortTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
    }

    public String time(Instant instant) {
        return instant == null ? "-" : timeFormat.format(instant);
    }

    public String shortTime(Instant instant) {
        return instant == null ? "-" : shortTimeFormat.format(instant);
    }

    public String money(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.of("en", "IN"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "₹" + nf.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    public String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100);
    }
}
