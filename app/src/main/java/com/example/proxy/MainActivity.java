package com.example.proxy;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.proxy.logger.DecisionLogger;
import com.example.proxy.mdnsDiscovery.EdgeDevice;
import com.example.proxy.mdnsDiscovery.EdgeDiscovery;
import com.example.proxy.mdnsDiscovery.EdgeRegistry;
import com.example.proxy.mdnsDiscovery.RoutingAlgorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private final EdgeRegistry edgeRegistry = new EdgeRegistry();
    private ProxyServer proxy;
    private EdgeDiscovery discovery;

    private RecyclerView rvEdges;
    private RecyclerView rvLogs;
    private TextView tvNoEdges;
    private ImageButton btnSettings;

    private EdgeAdapter edgeAdapter;
    private LogAdapter logAdapter;

    private Handler mainHandler;
    private Runnable uiRefreshRunnable;
    private ScheduledExecutorService maintenanceExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen awake for stress testing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        rvEdges = findViewById(R.id.rvEdges);
        rvLogs = findViewById(R.id.rvLogs);
        tvNoEdges = findViewById(R.id.tvNoEdges);
        btnSettings = findViewById(R.id.btnSettings);

        rvEdges.setLayoutManager(new LinearLayoutManager(this));
        edgeAdapter = new EdgeAdapter();
        rvEdges.setAdapter(edgeAdapter);

        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        logAdapter = new LogAdapter();
        rvLogs.setAdapter(logAdapter);

        btnSettings.setOnClickListener(v -> showSettingsDialog());

        mainHandler = new Handler(Looper.getMainLooper());
        uiRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                List<EdgeDevice> currentEdges = edgeRegistry.getAll();
                edgeAdapter.setEdges(currentEdges);
                if (currentEdges.isEmpty()) {
                    tvNoEdges.setVisibility(View.VISIBLE);
                } else {
                    tvNoEdges.setVisibility(View.GONE);
                }

                List<String> currentLogs = UiLogger.getLogs();
                logAdapter.setLogs(currentLogs);
                if (!currentLogs.isEmpty()) {
                    rvLogs.scrollToPosition(0);
                }

                mainHandler.postDelayed(this, 1000L);
            }
        };
        mainHandler.post(uiRefreshRunnable);

        // Start proxy server
        UiLogger.log("Starting Proxy Server...");
        try {
            proxy = new ProxyServer(edgeRegistry, 8080, UiLogger::log);
            proxy.start(0, false);
            UiLogger.log("Proxy Server started on port 8080.");
        } catch (Exception e) {
            UiLogger.log("Error starting proxy server: " + e.getMessage());
            Log.e("MainActivity", "Proxy start error", e);
        }

        // Start edge discovery
        UiLogger.log("Starting Edge Discovery...");
        discovery = new EdgeDiscovery(this, edgeRegistry, UiLogger::log);
        discovery.startDiscovery();

        // Initialize decision logger
        DecisionLogger.initialize(getApplicationContext());

        // Periodic maintenance (battery updates every 5 min)
        startPeriodicMaintenance();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Routing Algorithm");

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_routing_algorithm, null);
        RadioGroup rgAlgorithm = dialogView.findViewById(R.id.rgAlgorithm);

        RoutingAlgorithm current = edgeRegistry.getSelectedAlgorithm();
        if (current == RoutingAlgorithm.HIGHEST_BATTERY) {
            rgAlgorithm.check(R.id.rbHighestBattery);
        } else if (current == RoutingAlgorithm.RANDOM) {
            rgAlgorithm.check(R.id.rbRandom);
        } else if (current == RoutingAlgorithm.RTT_MS) {
            rgAlgorithm.check(R.id.rbRttMs);
        } else if (current == RoutingAlgorithm.BATTERY_CONSERVATION) {
            rgAlgorithm.check(R.id.rbBatteryConservation);
        }

        builder.setView(dialogView);
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();

        rgAlgorithm.setOnCheckedChangeListener((group, checkedId) -> {
            RoutingAlgorithm selectedAlgo = RoutingAlgorithm.RTT_MS;
            if (checkedId == R.id.rbHighestBattery) {
                selectedAlgo = RoutingAlgorithm.HIGHEST_BATTERY;
            } else if (checkedId == R.id.rbRandom) {
                selectedAlgo = RoutingAlgorithm.RANDOM;
            } else if (checkedId == R.id.rbRttMs) {
                selectedAlgo = RoutingAlgorithm.RTT_MS;
            } else if (checkedId == R.id.rbBatteryConservation) {
                selectedAlgo = RoutingAlgorithm.BATTERY_CONSERVATION;
            }

            edgeRegistry.setSelectedAlgorithm(selectedAlgo);
            UiLogger.log("Algorithm changed to: " + selectedAlgo.name());
            dialog.dismiss();
        });

        dialog.show();
    }

    private void startPeriodicMaintenance() {
        maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            if (discovery != null) {
                discovery.refreshKnownEdges();
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            UiLogger.log("Shutting down services...");
            if (mainHandler != null && uiRefreshRunnable != null) {
                mainHandler.removeCallbacks(uiRefreshRunnable);
            }
            if (maintenanceExecutor != null) {
                maintenanceExecutor.shutdown();
            }
            if (proxy != null) {
                proxy.stop();
            }
            if (discovery != null) {
                discovery.stopDiscovery();
            }
            DecisionLogger.close();
        } catch (Exception e) {
            Log.e("MainActivity", "Cleanup error", e);
        }
    }

    // --- RecyclerView Adapters ---

    private static class EdgeAdapter extends RecyclerView.Adapter<EdgeAdapter.EdgeViewHolder> {
        private final List<EdgeDevice> edges = new ArrayList<>();

        public void setEdges(List<EdgeDevice> newEdges) {
            edges.clear();
            edges.addAll(newEdges);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public EdgeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_edge_device, parent, false);
            return new EdgeViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull EdgeViewHolder holder, int position) {
            EdgeDevice edge = edges.get(position);
            holder.tvIp.setText("IP: " + edge.getIp());
            holder.tvBattery.setText("Battery: " + edge.getBattery());
            holder.tvAvgLongRtt.setText(String.format(Locale.getDefault(), "%.2f ms", edge.getAvgLongRtt()));
            holder.tvAvgShortRtt.setText(String.format(Locale.getDefault(), "%.2f ms", edge.getAvgShortRtt()));
            holder.tvActiveTasks.setText("Active Tasks: " + edge.getCurrentQueue());
            holder.tvStatus.setText("Status: " + edge.getStatus());
        }

        @Override
        public int getItemCount() {
            return edges.size();
        }

        static class EdgeViewHolder extends RecyclerView.ViewHolder {
            TextView tvIp, tvBattery, tvAvgLongRtt, tvAvgShortRtt, tvActiveTasks, tvStatus;

            public EdgeViewHolder(@NonNull View itemView) {
                super(itemView);
                tvIp = itemView.findViewById(R.id.tvIp);
                tvBattery = itemView.findViewById(R.id.tvBattery);
                tvAvgLongRtt = itemView.findViewById(R.id.tvAvgLongRtt);
                tvAvgShortRtt = itemView.findViewById(R.id.tvAvgShortRtt);
                tvActiveTasks = itemView.findViewById(R.id.tvActiveTasks);
                tvStatus = itemView.findViewById(R.id.tvStatus);
            }
        }
    }

    private static class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
        private final List<String> logs = new ArrayList<>();

        public void setLogs(List<String> newLogs) {
            logs.clear();
            logs.addAll(newLogs);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
            return new LogViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            holder.tvLogText.setText(logs.get(position));
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        static class LogViewHolder extends RecyclerView.ViewHolder {
            TextView tvLogText;

            public LogViewHolder(@NonNull View itemView) {
                super(itemView);
                tvLogText = itemView.findViewById(R.id.tvLogText);
            }
        }
    }
}
