package com.example.proxy.mdnsDiscovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import com.example.proxy.UiLogger;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class EdgeDiscovery {
    public static final String SERVICE_TYPE = "_proxy-edge._tcp.";

    private final Context context;
    private final EdgeRegistry edgeRegistry;
    private final UiLogger.LogListener logger;
    private final NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private final OkHttpClient client;
    private final ExecutorService executor;

    public EdgeDiscovery(Context context, EdgeRegistry edgeRegistry, UiLogger.LogListener logger) {
        this.context = context;
        this.edgeRegistry = edgeRegistry;
        this.logger = logger;
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.executor = Executors.newCachedThreadPool();

        this.client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    public void startDiscovery() {
        if (discoveryListener != null) {
            log("Discovery is already active.");
            Log.w("EdgeDiscovery", "Discovery is already active.");
            return;
        }
        log("Starting network service discovery for '" + SERVICE_TYPE + "'");
        Log.i("EdgeDiscovery", "Starting network service discovery for '" + SERVICE_TYPE + "'");

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                log("Discovery started successfully.");
                Log.d("EdgeDiscovery", "Discovery started successfully.");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                log("Service found: " + service.getServiceName());
                Log.d("EdgeDiscovery", "Service found: " + service.getServiceName());
                nsdManager.resolveService(service, createResolveListener());
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                log("Service lost: " + service.getServiceName() + ". Removing from registry.");
                Log.i("EdgeDiscovery", "Service lost: " + service.getServiceName());

                try {
                    String serviceName = service.getServiceName();
                    if (serviceName.startsWith("EdgeServer_")) {
                        String parsableIp = serviceName.substring("EdgeServer_".length());
                        String ipAddress = parsableIp.replace('-', '.');

                        edgeRegistry.remove(ipAddress);
                        log("Removed " + ipAddress + " from EdgeRegistry");
                        Log.i("EdgeDiscovery", "Successfully removed lost edge server: " + ipAddress);
                    } else {
                        log("Could not parse IP from lost service name: " + serviceName);
                        Log.w("EdgeDiscovery", "Could not parse IP from lost service name: " + serviceName);
                    }
                } catch (Exception e) {
                    log("Error while processing lost service: " + e.getMessage());
                    Log.e("EdgeDiscovery", "Error processing lost service", e);
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                log("Discovery stopped.");
                Log.i("EdgeDiscovery", "Discovery stopped.");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                log("Start discovery failed: Error code " + errorCode);
                Log.e("EdgeDiscovery", "Start discovery failed: Error code " + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                log("Stop discovery failed: Error code " + errorCode);
                Log.e("EdgeDiscovery", "Stop discovery failed: Error code " + errorCode);
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private NsdManager.ResolveListener createResolveListener() {
        return new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                log("Resolve failed for " + serviceInfo.getServiceName() + ": Error code " + errorCode);
                Log.e("EdgeDiscovery", "Resolve failed for " + serviceInfo.getServiceName() + ": Error code " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                String ip = serviceInfo.getHost().getHostAddress();
                int port = serviceInfo.getPort();
                log("Service resolved: " + serviceInfo.getServiceName() + " at " + ip + ":" + port);
                Log.i("EdgeDiscovery", "Service resolved: " + serviceInfo.getServiceName() + " at " + ip + ":" + port);

                executor.execute(() -> {
                    performHandshake(ip, port);
                    queryEdgeStatus(ip, port);
                });
            }
        };
    }

    private void performHandshake(String ip, int port) {
        String proxyIp = getLocalIpAddress();
        int proxyPort = 8080;

        String url = "http://" + ip + ":" + port + "/proxy-handshake?ip=" + proxyIp + "&port=" + proxyPort;
        RequestBody emptyBody = RequestBody.create(new byte[0], null);

        Request request = new Request.Builder()
                .url(url)
                .post(emptyBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            log("Handshake (POST) sent to " + ip + ". Proxy identified as " + proxyIp + ":" + proxyPort);
            Log.i("EdgeDiscovery", "Handshake POST successful for " + ip);
        } catch (Exception e) {
            log("Handshake POST failed to " + ip + ": " + e.getMessage());
            Log.e("EdgeDiscovery", "Handshake error", e);
        }
    }

    private void queryEdgeStatus(String ip, int port) {
        String url = "http://" + ip + ":" + port + "/status";
        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                JSONObject json = new JSONObject(body);
                String level = json.optString("level", "Unknown");
                String chargingStatus = json.optString("chargingStatus", "Unknown");
                String status = json.optString("status", "AVAILABLE");
                int androidVer = json.optInt("androidVersion", 0);
                long freeMem = json.optLong("freeMemory", 0);

                edgeRegistry.upsert(ip, level, "AVAILABLE", androidVer, freeMem);
                log("Successfully queried edge at " + ip + ". Added/updated to registry.");
                Log.d("EdgeDiscovery", "Successfully queried edge at " + ip + ". Added to registry.");
            } else {
                log("Failed to query status for " + ip + ". HTTP " + response.code());
                Log.w("EdgeDiscovery", "Failed to query status for " + ip + ". HTTP " + response.code());
                edgeRegistry.remove(ip);
            }
        } catch (Exception e) {
            log("Error querying status for " + ip + ": " + e.getMessage());
            Log.e("EdgeDiscovery", "Error querying status for " + ip + ": " + e.getMessage());
            edgeRegistry.remove(ip);
        }
    }

    public void refreshKnownEdges() {
        log("Starting periodic refresh of known edge servers...");
        List<EdgeDevice> knownEdges = edgeRegistry.getAll();
        if (knownEdges.isEmpty()) {
            log("No known edges to refresh.");
            return;
        }

        log("Refreshing " + knownEdges.size() + " edge(s).");
        for (EdgeDevice edge : knownEdges) {
            executor.execute(() -> queryEdgeStatus(edge.getIp(), 8080));
        }
    }

    public void stopDiscovery() {
        if (discoveryListener != null) {
            log("Stopping network service discovery.");
            Log.i("EdgeDiscovery", "Stopping network service discovery.");
            nsdManager.stopServiceDiscovery(discoveryListener);
            discoveryListener = null;
        }
        executor.shutdown();
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                Enumeration<InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("EdgeDiscovery", "Could not get local IP", ex);
        }
        return "127.0.0.1";
    }
}
