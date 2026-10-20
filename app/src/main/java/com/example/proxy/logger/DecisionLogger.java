package com.example.proxy.logger;

import android.content.Context;
import android.util.Log;

import com.example.proxy.mdnsDiscovery.EdgeDevice;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class DecisionLogger {

    private static FileWriter fileWriter = null;
    private static FileWriter queueFileWriter = null;
    private static boolean isInitialized = false;

    public static final ConcurrentHashMap<String, Decision> liveQueue = new ConcurrentHashMap<>();

    private DecisionLogger() {}

    public static synchronized void initialize(Context context) {
        if (isInitialized) return;

        try {
            File baseDir = context.getExternalFilesDir(null);
            File customDocsDir = new File(baseDir, "Documents");

            if (!customDocsDir.exists()) {
                boolean created = customDocsDir.mkdirs();
                Log.i("DecisionLogger", "Creating custom Documents folder: " + created);
            }

            File logFile = new File(customDocsDir, "proxy_decisions_detailed.csv");
            File queueFile = new File(customDocsDir, "proxy_queue_log.txt");

            boolean writeHeader = !logFile.exists() || logFile.length() == 0L;

            fileWriter = new FileWriter(logFile, true);
            if (writeHeader) {
                fileWriter.append(Decision.getCsvHeader());
                fileWriter.flush();
            }

            queueFileWriter = new FileWriter(queueFile, true);
            queueFileWriter.append("\n--- New Session Started: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date()))
                    .append(" ---\n");
            queueFileWriter.flush();

            isInitialized = true;
            Log.i("DecisionLogger", "✅ Logging initialized in custom folder: " + logFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e("DecisionLogger", "Failed to initialize file logger", e);
        }
    }

    private static void logQueueSnapshot(String action, String requestId) {
        try {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            List<String> currentQueueIds = new ArrayList<>(liveQueue.keySet());

            String logEntry = "[" + timestamp + "] ACTION: " + action + " | ID: " + requestId + " | ACTIVE_COUNT: " + currentQueueIds.size() + " | QUEUE: " + currentQueueIds + "\n";

            if (queueFileWriter != null) {
                queueFileWriter.append(logEntry);
                queueFileWriter.flush();
            }
        } catch (Exception e) {
            Log.e("DecisionLogger", "Failed to write queue snapshot", e);
        }
    }

    public static Decision createRecord(String requestPath, EdgeDevice chosenEdge, String requestID) {
        Decision decision = new Decision(
                requestID,
                System.currentTimeMillis(),
                requestPath,
                chosenEdge.getIp(),
                chosenEdge.getBattery()
        );

        liveQueue.put(decision.getRequestID(), decision);
        logQueueSnapshot("ADDED", decision.getRequestID());

        return decision;
    }

    public static synchronized void finalizeAndWrite(Decision decision) {
        if (!isInitialized) return;

        if (decision.getTimestampOfSendingResponse() == null) {
            decision.setTimestampOfSendingResponse(System.currentTimeMillis());
        }

        liveQueue.remove(decision.getRequestID());
        logQueueSnapshot("REMOVED", decision.getRequestID());

        try {
            if (fileWriter != null) {
                fileWriter.append(decision.toCsvLine());
                fileWriter.flush();
            }
        } catch (Exception e) {
            Log.e("DecisionLogger", "Failed to write to log file", e);
        }
    }

    public static synchronized void close() {
        try {
            if (fileWriter != null) {
                fileWriter.close();
            }
            if (queueFileWriter != null) {
                queueFileWriter.close();
            }
            isInitialized = false;
            Log.i("DecisionLogger", "Logger file closed.");
        } catch (Exception e) {
            Log.e("DecisionLogger", "Failed to close logger file", e);
        }
    }

    public static Decision getRecord(String requestId) {
        return liveQueue.get(requestId);
    }
}
