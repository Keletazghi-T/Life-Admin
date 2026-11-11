package com.lifeadmin.dto;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
/** Task dates are date-only ISO strings, never timestamps or inferred dates. */
public class StrictLocalDateDeserializer extends JsonDeserializer<LocalDate> {
    @Override public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        if (parser.currentToken() != JsonToken.VALUE_STRING || value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw InvalidFormatException.from(parser, "Use a date in YYYY-MM-DD format.", value, LocalDate.class);
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw InvalidFormatException.from(parser, "Use a year from 0001 through 9999.", value, LocalDate.class);
            return date;
        }
        catch (DateTimeParseException e) { throw InvalidFormatException.from(parser, "Use a valid calendar date.", value, LocalDate.class); }
    }
}
