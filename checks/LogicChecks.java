package com.s1ambient;

import java.time.LocalDateTime;

/** Dependency-free checks against the actual compiled app classes. */
public final class LogicChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        int[] codes = {0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65,
            66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99};
        for (int code : codes) {
            check(!WeatherController.Companion.condition(code).equals("Condition unavailable"), "Missing WMO code " + code);
        }
        check(WeatherController.Companion.condition(65).equals("Heavy Rain"), "Heavy rain");
        check(WeatherController.Companion.condition(999).equals("Condition unavailable"), "Unknown weather");
        LocalDateTime due = LocalDateTime.of(2026, 10, 7, 23, 59);
        LocalTask task = new LocalTask("test", "Example", due, false);
        check(!task.overdue(due.minusMinutes(1)), "Upcoming task");
        check(task.overdue(due), "Due minute boundary");
        check(task.overdue(due.plusMinutes(2)), "Midnight must not reset overdue state");
        task.setCompleted(true);
        check(!task.overdue(due.plusDays(1)), "Completed tasks must not be overdue");
        task.setCompleted(false);
        check(task.overdue(due.plusDays(1)), "Uncompleted task restores overdue state");
        System.out.println("Passed: all 28 WMO codes, unknown-code fallback, task due boundary, midnight and completion transitions.");
    }
}
