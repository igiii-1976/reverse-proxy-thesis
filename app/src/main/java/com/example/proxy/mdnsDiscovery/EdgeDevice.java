package com.example.proxy.mdnsDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EdgeDevice {
    private final String ip;
    private String battery;
    private String status;
    private int androidVersion;
    private final long lastSeen;
    private final List<Long> longRttHistory;
    private final List<Long> shortRttHistory;
    private double avgLongRtt;
    private double avgShortRtt;
    private int currentQueue;
    private long totalFreeMemory;

    public EdgeDevice(String ip, String battery, String status) {
        this.ip = ip;
        this.battery = battery;
        this.status = status;
        this.androidVersion = 0;
        this.lastSeen = System.currentTimeMillis();
        this.longRttHistory = Collections.synchronizedList(new ArrayList<>());
        this.shortRttHistory = Collections.synchronizedList(new ArrayList<>());
        this.avgLongRtt = 0.0;
        this.avgShortRtt = 0.0;
        this.currentQueue = 0;
        this.totalFreeMemory = 0;
    }

    public String getIp() {
        return ip;
    }

    public String getBattery() {
        return battery;
    }

    public void setBattery(String battery) {
        this.battery = battery;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAndroidVersion() {
        return androidVersion;
    }

    public void setAndroidVersion(int androidVersion) {
        this.androidVersion = androidVersion;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public List<Long> getLongRttHistory() {
        return longRttHistory;
    }

    public List<Long> getShortRttHistory() {
        return shortRttHistory;
    }

    public double getAvgLongRtt() {
        return avgLongRtt;
    }

    public void setAvgLongRtt(double avgLongRtt) {
        this.avgLongRtt = avgLongRtt;
    }

    public double getAvgShortRtt() {
        return avgShortRtt;
    }

    public void setAvgShortRtt(double avgShortRtt) {
        this.avgShortRtt = avgShortRtt;
    }

    public synchronized int getCurrentQueue() {
        return currentQueue;
    }

    public synchronized void setCurrentQueue(int currentQueue) {
        this.currentQueue = currentQueue;
    }

    public synchronized void incrementQueue() {
        this.currentQueue++;
    }

    public synchronized void decrementQueue() {
        if (this.currentQueue > 0) {
            this.currentQueue--;
        }
    }

    public long getTotalFreeMemory() {
        return totalFreeMemory;
    }

    public void setTotalFreeMemory(long totalFreeMemory) {
        this.totalFreeMemory = totalFreeMemory;
    }
}
