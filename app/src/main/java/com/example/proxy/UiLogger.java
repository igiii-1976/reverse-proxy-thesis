package com.example.proxy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

public class UiLogger {
    private static final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
    private static final int MAX_LOGS = 100;

    @FunctionalInterface
    public interface LogListener {
        void log(String message);
    }

    private UiLogger() {}

    public static void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = timestamp + " - " + message;
        logs.add(logEntry);

        while (logs.size() > MAX_LOGS) {
            logs.poll();
        }
    }

    public static List<String> getLogs() {
        List<String> list = new ArrayList<>(logs);
        Collections.reverse(list); // Show newest logs first
        return list;
    }
}
