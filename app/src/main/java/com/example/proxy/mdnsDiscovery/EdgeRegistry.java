package com.example.proxy.mdnsDiscovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class EdgeRegistry {
    private final ConcurrentHashMap<String, EdgeDevice> edges = new ConcurrentHashMap<>();
    private final ReentrantLock availabilityLock = new ReentrantLock();
    private final Condition availabilityCondition = availabilityLock.newCondition();

    private RoutingAlgorithm selectedAlgorithm = RoutingAlgorithm.RTT_MS;
    private final Random random = new Random();

    private int lastLongProbeIndex = 0;
    private int lastShortProbeIndex = 0;

    public RoutingAlgorithm getSelectedAlgorithm() {
        return selectedAlgorithm;
    }

    public void setSelectedAlgorithm(RoutingAlgorithm selectedAlgorithm) {
        this.selectedAlgorithm = selectedAlgorithm;
    }

    /**
     * Blocks if no edges are AVAILABLE (First-Come, First-Served)
     */
    public EdgeDevice getBestEdge(TaskType taskType) {
        if (selectedAlgorithm == RoutingAlgorithm.RANDOM) {
            return getRandomEdge();
        }

        availabilityLock.lock();
        try {
            EdgeDevice best = findBestByAlgorithm(taskType);

            while (best == null && !edges.isEmpty()) {
                availabilityCondition.await();
                best = findBestByAlgorithm(taskType);
            }
            return best;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            availabilityLock.unlock();
        }
    }

    private EdgeDevice findBestByAlgorithm(TaskType taskType) {
        List<EdgeDevice> availableOnes = new ArrayList<>();
        for (EdgeDevice edge : edges.values()) {
            if ("AVAILABLE".equals(edge.getStatus())) {
                availableOnes.add(edge);
            }
        }

        if (availableOnes.isEmpty()) {
            return null;
        }

        if (selectedAlgorithm == RoutingAlgorithm.RTT_MS) {
            return chooseLowestRtt(taskType, availableOnes);
        } else {
            return availableOnes.get(random.nextInt(availableOnes.size()));
        }
    }

    private EdgeDevice getRandomEdge() {
        List<EdgeDevice> all = getAll();
        if (all.isEmpty()) {
            return null;
        }
        return all.get(random.nextInt(all.size()));
    }

    private EdgeDevice chooseLowestRtt(TaskType taskType, List<EdgeDevice> availablePool) {
        // 1. Initial Round Robin for Edges with No History
        List<EdgeDevice> noHistory = new ArrayList<>();
        for (EdgeDevice edge : availablePool) {
            if (taskType == TaskType.LONG) {
                if (edge.getLongRttHistory().isEmpty()) {
                    noHistory.add(edge);
                }
            } else {
                if (edge.getShortRttHistory().isEmpty()) {
                    noHistory.add(edge);
                }
            }
        }
        noHistory.sort(Comparator.comparing(EdgeDevice::getIp));

        if (!noHistory.isEmpty()) {
            EdgeDevice edge;
            if (taskType == TaskType.LONG) {
                edge = noHistory.get((lastLongProbeIndex++) % noHistory.size());
            } else {
                edge = noHistory.get((lastShortProbeIndex++) % noHistory.size());
            }
            return edge;
        }

        // 2. Inactivity Check (Priority: Send to edges with 0 queue)
        List<EdgeDevice> inactiveEdges = new ArrayList<>();
        for (EdgeDevice edge : availablePool) {
            if (edge.getCurrentQueue() == 0) {
                inactiveEdges.add(edge);
            }
        }

        if (!inactiveEdges.isEmpty()) {
            EdgeDevice target = inactiveEdges.get(0);
            int burstLimit = (target.getAndroidVersion() <= 25) ? 8 : 10;

            if (target.getCurrentQueue() < burstLimit) {
                return target;
            }
        }

        // 4. Default: Send to lowest Average RTT among available edges
        EdgeDevice best = null;
        double minRtt = Double.MAX_VALUE;

        for (EdgeDevice edge : availablePool) {
            double rtt = (taskType == TaskType.LONG) ? edge.getAvgLongRtt() : edge.getAvgShortRtt();
            if (rtt < minRtt) {
                minRtt = rtt;
                best = edge;
            }
        }

        return best;
    }

    public void updateStatus(String ip, String newStatus) {
        EdgeDevice edge = edges.get(ip);
        if (edge == null) return;

        availabilityLock.lock();
        try {
            boolean wasBusy = "BUSY".equals(edge.getStatus());
            edge.setStatus(newStatus);
            if (wasBusy && "AVAILABLE".equals(newStatus)) {
                availabilityCondition.signal();
            }
        } finally {
            availabilityLock.unlock();
        }
    }

    public synchronized void updateRtt(String ip, long newRtt, TaskType taskType) {
        EdgeDevice edge = edges.get(ip);
        if (edge == null) return;

        List<Long> history = (taskType == TaskType.LONG) ? edge.getLongRttHistory() : edge.getShortRttHistory();
        history.add(newRtt);

        double sum = 0.0;
        synchronized (history) {
            for (Long val : history) {
                sum += val;
            }
            double avg = history.isEmpty() ? 0.0 : sum / history.size();
            if (taskType == TaskType.LONG) {
                edge.setAvgLongRtt(avg);
            } else {
                edge.setAvgShortRtt(avg);
            }
        }
    }

    public void incrementQueue(String ip) {
        EdgeDevice edge = edges.get(ip);
        if (edge != null) {
            edge.incrementQueue();
        }
    }

    public void decrementQueue(String ip) {
        EdgeDevice edge = edges.get(ip);
        if (edge != null) {
            edge.decrementQueue();
        }
    }

    public synchronized void upsert(String ip, String battery, String status, int androidVer, long freeMem) {
        EdgeDevice existing = edges.get(ip);
        if (existing != null) {
            existing.setBattery(battery);
            existing.setStatus(status);
            existing.setAndroidVersion(androidVer);
            existing.setTotalFreeMemory(freeMem);
        } else {
            EdgeDevice newDevice = new EdgeDevice(ip, battery, status);
            newDevice.setAndroidVersion(androidVer);
            newDevice.setTotalFreeMemory(freeMem);
            edges.put(ip, newDevice);
        }
    }

    public synchronized void upsert(String ip, String battery, String status) {
        upsert(ip, battery, status, 0, 0);
    }

    public synchronized List<EdgeDevice> getAll() {
        return new ArrayList<>(edges.values());
    }

    public synchronized void remove(String ip) {
        edges.remove(ip);
    }

    public synchronized void clear() {
        edges.clear();
    }
}
