package com.visitor.rfid;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RfidGui extends JFrame implements RfidReaderTask.TagListener {

    private static final String API_ENDPOINT = "http://localhost:7777/api/v1/movement/add";

    private DefaultTableModel tableModel;
    private JTextArea connectionLogArea;
    private JTextArea apiLogArea;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduler;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Map<String, RfidReaderTask> activeReaders = new HashMap<>();
    private final Map<String, String> ipToNameMap = new HashMap<>();
    private final Map<String, JLabel> statusLabels = new HashMap<>(); // New map for status labels

    private final Map<String, Long> tagTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenUiMap = new ConcurrentHashMap<>();

    private final java.util.Queue<Long> connectionLogTimestamps = new java.util.LinkedList<>();
    private final java.util.Queue<Long> apiLogTimestamps = new java.util.LinkedList<>();

    public RfidGui() {
        bypassSslVerification();
        setTitle("Multi-IP RFID Scanner");
        setSize(900, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        executorService = Executors.newCachedThreadPool();

        List<RfidConfig.ReaderDevice> devices = RfidConfig.loadDevicesFromIni("config.ini");

        for (RfidConfig.ReaderDevice device : devices) {
            ipToNameMap.put(device.ip, device.name);
        }

        JPanel topPanel = new JPanel(new BorderLayout());
        JPanel controlPanel = buildControlPanel(devices);
        JScrollPane controlScrollPane = new JScrollPane(controlPanel);
        controlScrollPane.setPreferredSize(new Dimension(800, 150));
        controlScrollPane.setBorder(BorderFactory.createTitledBorder("Reader Controls & Status"));
        topPanel.add(controlScrollPane, BorderLayout.CENTER);

        // Add settings icon
        try {
            JButton settingsButton = new JButton("⚙");
            settingsButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
            settingsButton.setToolTipText("Open Config Settings");
            settingsButton.setFocusPainted(false);
            settingsButton.setContentAreaFilled(false);
            settingsButton.setBorderPainted(false);
            settingsButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            settingsButton.addActionListener(e -> {
                SettingsWindow settingsWindow = new SettingsWindow(this);
                settingsWindow.setVisible(true);
            });

            JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            rightPanel.add(settingsButton);
            topPanel.add(rightPanel, BorderLayout.EAST);
        } catch (Exception e) {
//            e.printStackTrace();
        }

        add(topPanel, BorderLayout.NORTH);

        String[] columns = { "Gate Number", "Reader IP", "Tag Number", "Last Read Time" };
        tableModel = new DefaultTableModel(columns, 0);
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        JScrollPane tableScrollPane = new JScrollPane(table);
        tableScrollPane.setBorder(BorderFactory.createTitledBorder("Scanned Tags"));
        add(tableScrollPane, BorderLayout.CENTER);

        connectionLogArea = new JTextArea(8, 30);
        connectionLogArea.setEditable(false);
        JScrollPane connLogScroll = new JScrollPane(connectionLogArea);
        connLogScroll.setBorder(BorderFactory.createTitledBorder("Connection Logs"));

        apiLogArea = new JTextArea(8, 30);
        apiLogArea.setEditable(false);
        JScrollPane apiLogScroll = new JScrollPane(apiLogArea);
        apiLogScroll.setBorder(BorderFactory.createTitledBorder("API Logs"));

        JPanel logsPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        logsPanel.add(connLogScroll);
        logsPanel.add(apiLogScroll);
        add(logsPanel, BorderLayout.SOUTH);

        if (devices.isEmpty()) {
            connectionLogArea.append("System: No devices found. Please check your config.ini file.\n");
            apiLogArea.append("System: No devices found.\n");
        } else {
            // Auto start all readers
            for (RfidConfig.ReaderDevice device : devices) {
                startReader(device.ip);
            }
        }

        // Start 1-minute cleanup scheduler
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::cleanupOldRecords, 1, 1, TimeUnit.MINUTES);
    }

    private void bypassSslVerification() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            // Initialize the SSLContext with our custom TrustManager
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            // Apply it globally to all HttpsURLConnections in this app
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

        } catch (Exception e) {
//            System.err.println("Failed to bypass SSL: " + e.getMessage());
        }
    }

    private JPanel buildControlPanel(List<RfidConfig.ReaderDevice> devices) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        for (RfidConfig.ReaderDevice device : devices) {
            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));

            JLabel ipLabel = new JLabel(device.name + " (" + device.ip + ")");
            ipLabel.setPreferredSize(new Dimension(250, 25));

            JLabel statusLabel = new JLabel("Initializing...");
            statusLabel.setPreferredSize(new Dimension(200, 25));
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
            statusLabels.put(device.ip, statusLabel);

            rowPanel.add(ipLabel);
            rowPanel.add(statusLabel);

            panel.add(rowPanel);
        }
        return panel;
    }

    private void startReader(String ip) {
        if (!activeReaders.containsKey(ip)) {
            RfidReaderTask task = new RfidReaderTask(ip, this);
            activeReaders.put(ip, task);
            executorService.execute(task);
            onLog(ip, "Initiating connection...");
        }
    }

    private void stopReader(String ip) {
        // Method kept for teardown or future manual overrides, though unused on UI.
        RfidReaderTask task = activeReaders.get(ip);
        if (task != null) {
            task.stopReader();
            activeReaders.remove(ip);
            onLog(ip, "Stop command sent.");
        }
    }

    @Override
    public void onConnectionStateChange(String ip, String stateMessage, Color color) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = statusLabels.get(ip);
            if (label != null) {
                label.setText(stateMessage);
                label.setForeground(color);
            }
        });
    }

    @Override
    public void onTagRead(String ip, String tagData) {
        long currentTimeMs = System.currentTimeMillis();
        // Create a unique key for this specific reader and tag combination
        String uniqueTagKey = ip + "-" + tagData;

        lastSeenUiMap.put(uniqueTagKey, currentTimeMs);

        long lastSeenMs = tagTimestamps.getOrDefault(uniqueTagKey, 0L);

        // Check if 60,000 milliseconds (1 minute) have passed
        if (currentTimeMs - lastSeenMs > 60000) {
            // Update the timestamp to now
            tagTimestamps.put(uniqueTagKey, currentTimeMs);

            // Fire the API call in the background
            sendTagToApiBackground(ip, tagData);
        }

        // We still update the UI table every time so you know the hardware is working
        SwingUtilities.invokeLater(() -> {
            String now = LocalTime.now().format(timeFormatter);
            String name = ipToNameMap.getOrDefault(ip, "Unknown");
            boolean tagExists = false;

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (tableModel.getValueAt(i, 1).equals(ip) && tableModel.getValueAt(i, 2).equals(tagData)) {
                    tableModel.setValueAt(now, i, 3);
                    tagExists = true;
                    break;
                }
            }

            if (!tagExists) {
                tableModel.addRow(new Object[] { name, ip, tagData, now });
            }
        });
    }

    // New: Date formatter matching your required yyyy-mm-dd HH:mm:ss format
    private final DateTimeFormatter apiTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private void sendTagToApiBackground(String ip, String tagData) {
        executorService.execute(() -> {
            try {
                String name = ipToNameMap.getOrDefault(ip, "Unknown");

                // Get the current time formatted exactly as the API expects
                String passTime = java.time.LocalDateTime.now().format(apiTimeFormatter);

                URL url = new URL(API_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                // Updated JSON payload to match your destructuring: { tenantId, cardNo, gate,
                // passTime }
                String jsonInputString = String.format(
                        "{\"tenantId\": \"a1b2c3d4\", \"cardNo\": \"%s\", \"gate\": \"%s\", \"passTime\": \"%s\"}",
                        tagData, name, passTime);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    onApiLog(ip, "API Success: Tag data pushed to server.");
                } else {
                    onApiLog(ip, "API Failed: HTTP " + code);
                }

                conn.disconnect();

            } catch (Exception e) {
                onApiLog(ip, "API Error: Could not connect to backend (" + e.getMessage() + ")");
            }
        });
    }

    public void onApiLog(String ip, String message) {
        SwingUtilities.invokeLater(() -> {
            String name = ipToNameMap.getOrDefault(ip, "Unknown");
            String prefix = LocalTime.now().format(timeFormatter) + " ";
            apiLogArea.append(prefix + "[" + name + " | " + ip + "] " + message + "\n");
            apiLogTimestamps.add(System.currentTimeMillis());
            apiLogArea.setCaretPosition(apiLogArea.getDocument().getLength());
        });
    }

    @Override
    public void onLog(String ip, String message) {
        SwingUtilities.invokeLater(() -> {
            String name = ipToNameMap.getOrDefault(ip, "Unknown");
            String prefix = LocalTime.now().format(timeFormatter) + " ";
            connectionLogArea.append(prefix + "[" + name + " | " + ip + "] " + message + "\n");
            connectionLogTimestamps.add(System.currentTimeMillis());
            connectionLogArea.setCaretPosition(connectionLogArea.getDocument().getLength());
        });
    }

    private void cleanupOldRecords() {
        long now = System.currentTimeMillis();
        long oneHourMs = 60 * 60 * 1000L;

        SwingUtilities.invokeLater(() -> {
            // Cleanup Scanned Tags Table
            for (int i = tableModel.getRowCount() - 1; i >= 0; i--) {
                String ip = (String) tableModel.getValueAt(i, 1);
                String tagData = (String) tableModel.getValueAt(i, 2);
                String uniqueTagKey = ip + "-" + tagData;

                Long lastSeen = lastSeenUiMap.get(uniqueTagKey);
                if (lastSeen == null || (now - lastSeen) > oneHourMs) {
                    tableModel.removeRow(i);
                    lastSeenUiMap.remove(uniqueTagKey);
                    tagTimestamps.remove(uniqueTagKey);
                }
            }

            // Cleanup Connection Logs
            while (!connectionLogTimestamps.isEmpty() && (now - connectionLogTimestamps.peek()) > oneHourMs) {
                connectionLogTimestamps.poll();
                try {
                    int end = connectionLogArea.getLineEndOffset(0);
                    connectionLogArea.replaceRange("", 0, end);
                } catch (javax.swing.text.BadLocationException e) {
//                    e.printStackTrace();
                }
            }

            // Cleanup API Logs
            while (!apiLogTimestamps.isEmpty() && (now - apiLogTimestamps.peek()) > oneHourMs) {
                apiLogTimestamps.poll();
                try {
                    int end = apiLogArea.getLineEndOffset(0);
                    apiLogArea.replaceRange("", 0, end);
                } catch (javax.swing.text.BadLocationException e) {
//                    e.printStackTrace();
                }
            }
        });
    }
}
