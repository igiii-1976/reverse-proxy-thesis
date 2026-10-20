package com.example.proxy.mdnsDiscovery;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NetworkScanner {
    private final Context context;
    private final EdgeRegistry edgeRegistry;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService scanExecutor;
    private final OkHttpClient client;

    public NetworkScanner(Context context, EdgeRegistry edgeRegistry) {
        this.context = context;
        this.edgeRegistry = edgeRegistry;
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.scanExecutor = Executors.newFixedThreadPool(32);

        this.client = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
    }

    public void startScanPeriodically(long intervalMs) {
        scheduler.scheduleAtFixedRate(this::scanNetwork, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void startRefreshPeriodically(long intervalMs) {
        scheduler.scheduleAtFixedRate(this::refreshKnownEdges, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdown();
        scanExecutor.shutdown();
    }

    private void scanNetwork() {
        String proxyIP = getIPAddress();
        if (proxyIP == null) return;

        int lastDotIndex = proxyIP.lastIndexOf('.');
        if (lastDotIndex == -1) return;
        String subnet = proxyIP.substring(0, lastDotIndex);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            String ip = subnet + "." + i;
            if (!ip.equals(proxyIP)) {
                futures.add(scanExecutor.submit(() -> {
                    String url = "http://" + ip + ":8080/status";
                    Request request = new Request.Builder().url(url).build();
                    try (Response response = client.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String body = response.body().string();
                            Log.d("NetworkScanner", "✅ " + ip + " responded: " + body);
                            JSONObject json = new JSONObject(body);
                            String level = json.optString("level", "Unknown");
                            String status = json.optString("status", "Unknown");
                            edgeRegistry.upsert(ip, level, status);
                        } else {
                            Log.d("NetworkScanner", "❌ " + ip + " HTTP " + response.code());
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }));
            }
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshKnownEdges() {
        List<EdgeDevice> edges = edgeRegistry.getAll();
        for (EdgeDevice edge : edges) {
            String ip = edge.getIp();
            String url = "http://" + ip + ":8080/status";

            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        String level = json.optString("level", "Unknown");
                        String status = json.optString("status", "Unknown");

                        edgeRegistry.upsert(ip, level, status);
                        Log.i("NetworkScanner", "Refreshed edge " + ip + " (" + level + ", " + status + ")");
                    } else {
                        Log.w("NetworkScanner", "Edge " + ip + " not responding (HTTP " + response.code() + ")");
                    }
                }
            } catch (Exception e) {
                Log.w("NetworkScanner", "Edge " + ip + " refresh failed: " + e.getMessage());
            }
        }
    }

    private String getIPAddress() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network network = cm.getActiveNetwork();
            if (network == null) return null;
            LinkProperties props = cm.getLinkProperties(network);
            if (props == null) return null;

            for (LinkAddress address : props.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    return address.getAddress().getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.e("NetworkScanner", "Failed to get subnet base", e);
        }
        return null;
    }
}
