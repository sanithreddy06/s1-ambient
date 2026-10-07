package com.s1ambient;

import java.time.LocalDateTime;

/** Dependency-free checks against the actual compiled app classes. */
public final class LogicChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        java.util.Locale previousLocale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);
            String endpoint = WeatherController.Companion.endpoint(12.5, -45.25);
            check(endpoint.contains("latitude=12.50000&longitude=-45.25000"), "Device coordinates and locale-independent decimals");
            check(endpoint.endsWith("timezone=auto"), "Weather timezone follows selected coordinates");
            check(WeatherController.Companion.endpoint(0, 0).contains("latitude=0.00000"), "Zero coordinates are valid");
            try { WeatherController.Companion.endpoint(Double.NaN, 0); throw new AssertionError("NaN accepted"); }
            catch (IllegalArgumentException expected) {}
            try { WeatherController.Companion.endpoint(0, 181); throw new AssertionError("Invalid longitude accepted"); }
            catch (IllegalArgumentException expected) {}
        } finally { java.util.Locale.setDefault(previousLocale); }
        check(WeatherLocation.Companion.validFix(12, 34, 3000f, 1000), "Approximate location is sufficient for weather");
        check(!WeatherLocation.Companion.validFix(12, 34, 10001f, 1000), "Reject inaccurate fixes");
        check(!WeatherLocation.Companion.validFix(12, 34, 100f, 1800001), "Reject stale platform fixes");
        check(!WeatherLocation.Companion.validFix(12, 34, 100f, -1), "Reject future platform timestamps");
        check(!WeatherLocation.Companion.validFix(91, 34, 100f, 0), "Reject invalid latitude");
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
        System.out.println("Passed: device-coordinate URLs, approximate/fresh fix validation, all 28 WMO codes, unknown-code fallback, task due boundary, midnight and completion transitions.");
    }
}
