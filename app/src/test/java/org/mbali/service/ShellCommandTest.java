package org.mbali.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ShellCommandTest {
    private static String[] command(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new String[] {java, "-cp", classes, Child.class.getName(), mode};
    }

    @Test
    void stopsACommandWhoseOutputNeverCloses() throws Exception {
        String[] command = command("sleep");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertTrue(ShellCommand.output(StandardCharsets.UTF_8, Duration.ofMillis(300), command).isEmpty()));
    }

    @Test
    void drainsOutputLargerThanThePipeBuffer() throws Exception {
        assertEquals("x".repeat(200_000),
                ShellCommand.output(StandardCharsets.UTF_8, command("large")).orElseThrow());
    }

    @Test
    void rejectsNonZeroExitCodes() throws Exception {
        assertTrue(ShellCommand.output(StandardCharsets.UTF_8, command("fail")).isEmpty());
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "sleep" -> Thread.sleep(20_000);
                case "large" -> System.out.print("x".repeat(200_000));
                case "fail" -> System.exit(1);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
