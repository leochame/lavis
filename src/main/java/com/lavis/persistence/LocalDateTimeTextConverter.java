package com.lavis.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * SQLite stores timestamps in TEXT in this project (see migrations). However, SQLite JDBC's
 * {@code ResultSet#getTimestamp()} expects a formatted date string like "yyyy-MM-dd HH:mm:ss.SSS"
 * and will fail if older rows contain epoch millis (e.g. "1769584998276").
 *
 * <p>This converter forces Hibernate to read/write LocalDateTime as TEXT via getString()/setString(),
 * and is tolerant to multiple legacy formats.</p>
 */
@Converter(autoApply = true)
public class LocalDateTimeTextConverter implements AttributeConverter<LocalDateTime, String> {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    // Matches SQLite datetime('now') default: "YYYY-MM-DD HH:MM:SS"
    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    );

    private static final DateTimeFormatter WRITE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public String convertToDatabaseColumn(LocalDateTime attribute) {
        if (attribute == null) return null;
        return attribute.format(WRITE_FORMAT);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        String s = dbData.trim();
        if (s.isEmpty()) return null;

        // Legacy: epoch seconds/millis stored as string
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                long v = Long.parseLong(s);
                // Heuristic: 13 digits => millis, 10 digits => seconds
                Instant instant = (s.length() <= 10) ? Instant.ofEpochSecond(v) : Instant.ofEpochMilli(v);
                return LocalDateTime.ofInstant(instant, ZONE);
            } catch (NumberFormatException ignored) {
                // fall through to formatter parsing
            }
        }

        for (DateTimeFormatter f : FORMATTERS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }

        // Last resort: try ISO parser (sometimes includes zone-less variants)
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unsupported timestamp value for LocalDateTime: '" + dbData + "'", e);
        }
    }
}


