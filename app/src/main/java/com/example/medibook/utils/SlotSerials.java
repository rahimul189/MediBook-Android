package com.example.medibook.utils;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.example.medibook.models.InventorySlot;

/** Display-only numbers allocated durably in the Firestore inventory namespace, never list positions. */
public final class SlotSerials {
    public static final int MAX = 1_000_000;
    private SlotSerials() { }
    public static List<InventorySlot> publicationOrder(List<InventorySlot> slots) {
        List<InventorySlot> sorted = new ArrayList<>(slots);
        sorted.sort(Comparator.comparing((InventorySlot s) -> s.startAt)
                .thenComparing(s -> s.endAt).thenComparing(s -> s.id));
        return sorted;
    }
    public static int next(long last) {
        if (last < 0 || last >= MAX) throw new IllegalArgumentException("Invalid serial counter");
        return (int) last + 1;
    }
    public static String label(int number, int startMinute, int endMinute) {
        if (number > 0 && number <= MAX) return String.format(Locale.ROOT, "#%02d", number);
        // Explicitly fixed time-slot identifier for legacy/unassigned records, never a computed rank.
        return "Time slot " + DhakaDateTime.formatTime12Hour(startMinute) + " – " + DhakaDateTime.formatTime12Hour(endMinute);
    }
    /** Old RTDB keys remain a wire compatibility detail; numbers come ONLY from Firestore sidecars. */
    public static String legacyDisplay(String token, long midnight, Map<String, Integer> numbers) {
        if (token == null || !token.matches("Q[0-9]{4}")) return "Time slot";
        int start = Integer.parseInt(token.substring(1)) - 1;
        if (start < 0 || start > 1439) return "Time slot";
        String prefix = "s" + (midnight + start * 60000L) + "-";
        String result = null;
        for (Map.Entry<String, Integer> entry : numbers.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            try {
                long endMillis = Long.parseLong(entry.getKey().substring(prefix.length()));
                int end = (int) ((endMillis - midnight) / 60000L);
                if (end <= start || end > 1439) continue;
                if (result != null) return "Time slot " + DhakaDateTime.formatTime12Hour(start);
                result = label(entry.getValue(), start, end);
            } catch (NumberFormatException invalid) { /* Ignore malformed legacy paths. */ }
        }
        return result == null ? "Time slot " + DhakaDateTime.formatTime12Hour(start) : result;
    }
}
