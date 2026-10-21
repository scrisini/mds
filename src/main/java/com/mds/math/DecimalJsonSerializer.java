package com.mds.math;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/** Writes a {@link Decimal} as a JSON number straight from its string form. */
public class DecimalJsonSerializer extends StdSerializer<Decimal> {

    public DecimalJsonSerializer() {
        super(Decimal.class);
    }

    @Override
    public void serialize(Decimal value, JsonGenerator generator, SerializationContext context) {
        generator.writeNumber(value.toString());
    }
}
