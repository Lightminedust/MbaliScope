package org.mbali.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class ProcessSnapshotTest {

    private static final Instant NINE_AM = Instant.parse("2026-09-13T09:00:00Z");

    private static ProcessSnapshot process(long pid, Optional<Instant> start) {
        return new ProcessSnapshot(pid, OptionalLong.of(4), "chrome.exe", start,
                Optional.of(Duration.ofSeconds(12)), OptionalLong.of(250_000_000L),
                Optional.of("user"));
    }

    @Test
    void samePidAndSameStartIsTheSameProcess() {
        assertTrue(process(4242, Optional.of(NINE_AM))
                .isSameProcessAs(process(4242, Optional.of(NINE_AM))));
    }

    @Test
    void aReusedPidIsNotTheSameProcess() {
        // Windows a recycle le PID : meme numero, mais un processus lance plus tard
        ProcessSnapshot before = process(4242, Optional.of(NINE_AM));
        ProcessSnapshot after = process(4242, Optional.of(NINE_AM.plusSeconds(600)));

        assertFalse(before.isSameProcessAs(after));
    }

    @Test
    void withoutAStartTimeNothingCanBeAffirmed() {
        // Cas frequent : Windows refuse l'acces aux processus systeme
        ProcessSnapshot unknown = process(4242, Optional.empty());

        assertFalse(unknown.isSameProcessAs(process(4242, Optional.empty())));
        assertFalse(unknown.isSameProcessAs(process(4242, Optional.of(NINE_AM))));
    }

    @Test
    void anUnknownMemoryIsNotZero() {
        ProcessSnapshot system = new ProcessSnapshot(4, OptionalLong.of(0), "System",
                Optional.empty(), Optional.empty(), OptionalLong.empty(), Optional.empty());

        assertTrue(system.memoryBytes().isEmpty(), "inconnue, et non pas 0 octet");
        assertEquals("inconnue", system.memoryBytes().isPresent() ? "connue" : "inconnue");
    }

    @Test
    void aBlankNameBecomesReadable() {
        ProcessSnapshot nameless = new ProcessSnapshot(7, OptionalLong.empty(), "  ",
                Optional.empty(), Optional.empty(), OptionalLong.empty(), Optional.empty());

        assertEquals(ProcessSnapshot.UNKNOWN_NAME, nameless.name());
    }

    @Test
    void rejectsImpossibleValues() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessSnapshot(-1,
                OptionalLong.empty(), "x", Optional.empty(), Optional.empty(),
                OptionalLong.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessSnapshot(1,
                OptionalLong.empty(), "x", Optional.empty(), Optional.empty(),
                OptionalLong.of(-5), Optional.empty()));
        // Un Optional ne doit jamais etre null lui-meme
        assertThrows(NullPointerException.class, () -> new ProcessSnapshot(1,
                null, "x", Optional.empty(), Optional.empty(),
                OptionalLong.empty(), Optional.empty()));
    }
}
