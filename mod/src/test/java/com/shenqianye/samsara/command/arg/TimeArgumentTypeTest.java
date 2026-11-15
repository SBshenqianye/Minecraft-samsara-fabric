package com.shenqianye.samsara.command.arg;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeArgumentTypeTest {

    private static String parse(String input) throws CommandSyntaxException {
        StringReader reader = new StringReader(input);
        return TimeArgumentType.time().parse(reader);
    }

    @Test
    void acceptsClockAndIsoTimes() throws Exception {
        assertEquals("23:28", parse("23:28"));
        assertEquals("2026-10-28T23:28", parse("2026-10-28T23:28"));
        assertEquals("1797343583000", parse("1797343583000"));
        assertEquals("2026-10-28", parse("2026-10-28"));
    }

    @Test
    void stopsAtWhitespaceSoLaterArgumentsStillParse() throws Exception {
        StringReader reader = new StringReader("23:28 2.0");
        assertEquals("23:28", TimeArgumentType.time().parse(reader));
        assertEquals(' ', reader.peek());
        reader.skip();
        assertEquals("2.0", reader.readString());
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(CommandSyntaxException.class, () -> parse(""));
        assertThrows(CommandSyntaxException.class, () -> parse("   "));
    }
}
