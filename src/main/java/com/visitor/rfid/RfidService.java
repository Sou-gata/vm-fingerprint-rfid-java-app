package com.visitor.rfid;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
// import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RfidService implements RfidReaderTask.TagListener {
    private static final Logger logger = LogManager.getLogger(RfidService.class);

//    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final DateTimeFormatter apiTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final Map<String, RfidReaderTask> activeReaders = new HashMap<>();

    private final Map<String, String> ipToNameMap = new HashMap<>();

    private final Map<String, Long> tagTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();

    public RfidService() {
        bypassSslVerification();

        List<RfidConfig.ReaderDevice> devices = RfidConfig.loadDevicesFromIni("config.ini");

        if (devices.isEmpty()) {
            logger.error("SYSTEM: No devices found. Please check your config.ini file.");
            return;
        }

        for (RfidConfig.ReaderDevice device : devices) {
            ipToNameMap.put(device.ip, device.name);
        }

        // Auto-start all readers
        for (RfidConfig.ReaderDevice device : devices) {
            startReader(device.ip);
        }

        // Hourly cleanup of stale tag records
        scheduler.scheduleAtFixedRate(this::cleanupOldRecords, 1, 1, TimeUnit.MINUTES);

        logger.info("SYSTEM: RfidService started. Monitoring {} reader(s).", devices.size());
    }

    // -------------------------------------------------------------------------
    // Reader lifecycle
    // -------------------------------------------------------------------------

    private void startReader(String ip) {
        if (!activeReaders.containsKey(ip)) {
            RfidReaderTask task = new RfidReaderTask(ip, this);
            activeReaders.put(ip, task);
            executorService.execute(task);
            onLog(ip, "Initiating connection...");
        }
    }

    public void stopReader(String ip) {
        RfidReaderTask task = activeReaders.get(ip);
        if (task != null) {
            task.stopReader();
            activeReaders.remove(ip);
            onLog(ip, "Stop command sent.");
        }
    }

    public void shutdown() {
        for (String ip : activeReaders.keySet()) {
            stopReader(ip);
        }
        scheduler.shutdownNow();
        executorService.shutdownNow();
        logger.info("SYSTEM: RfidService shut down.");
    }

    @Override
    public void onConnectionStateChange(String ip, String stateMessage, java.awt.Color color) {
        String colorLabel = colorToLabel(color);
        logger.info("[{}] [{}] {}", ip, colorLabel, stateMessage);
    }

    @Override
    public void onTagRead(String ip, String tagData) {
        long currentTimeMs = System.currentTimeMillis();
        String uniqueTagKey = ip + "-" + tagData;

        lastSeenMap.put(uniqueTagKey, currentTimeMs);

        long lastSeenMs = tagTimestamps.getOrDefault(uniqueTagKey, 0L);

        if (currentTimeMs - lastSeenMs > 60_000) {
            tagTimestamps.put(uniqueTagKey, currentTimeMs);
            sendTagToApiBackground(ip, tagData);
        }

        String name = ipToNameMap.getOrDefault(ip, "Unknown");
        logger.debug("[TAG READ] [{} | {}] Tag: {}", name, ip, tagData);
    }

    @Override
    public void onLog(String ip, String message) {
        log(ip, message);
    }

    private void sendTagToApiBackground(String ip, String tagData) {
        executorService.execute(() -> {
            try {
                String name = ipToNameMap.getOrDefault(ip, "Unknown");
                String passTime = LocalDateTime.now().format(apiTimeFormatter);

                String endpoint = RfidConfig.endPoint;
                if (endpoint == null || endpoint.isEmpty()) {
                    logger.error("API Error [{}]: endPoint is not configured in config.ini", ip);
                    return;
                }

                @SuppressWarnings("deprecation")
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                String jsonBody = String.format(
                        "{\"tenantId\": \"a1b2c3d4\", \"cardNo\": \"%s\", \"gate\": \"%s\", \"passTime\": \"%s\"}",
                        tagData, name, passTime);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonBody.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    logger.info("API Success [{}]: Tag data pushed to server (HTTP {}).", ip, code);
                } else {
                    logger.warn("API Failed [{}]: HTTP {}", ip, code);
                }

                conn.disconnect();

            } catch (Exception e) {
                logger.error("API Error [{}]: Could not connect to backend ({})", ip, e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private void cleanupOldRecords() {
        long now = System.currentTimeMillis();
        long oneHourMs = 60L * 60 * 1000;

        lastSeenMap.entrySet().removeIf(entry -> {
            if ((now - entry.getValue()) > oneHourMs) {
                tagTimestamps.remove(entry.getKey());
                return true;
            }
            return false;
        });

        logger.debug("Cleanup complete. Active tag keys: {}", lastSeenMap.size());
    }

    // -------------------------------------------------------------------------
    // SSL bypass (identical to RfidGui)
    // -------------------------------------------------------------------------

    private void bypassSslVerification() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] c, String a) {
                        }

                        public void checkServerTrusted(X509Certificate[] c, String a) {
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            logger.error("Failed to bypass SSL: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void log(String ip, String message) {
        logger.info("[{}] {}", ip, message);
    }

    private void apiLog(String ip, String message) {
        logger.info("[API] [{}] {}", ip, message);
    }

    /**
     * Converts the AWT Color used by RfidReaderTask into a readable status label.
     */
    private String colorToLabel(java.awt.Color color) {
        if (color == null)
            return "UNKNOWN";
        if (color.equals(java.awt.Color.ORANGE))
            return "CONNECTING";
        if (color.equals(java.awt.Color.RED))
            return "RETRYING";
        if (color.getRed() == 0 && color.getGreen() == 153)
            return "CONNECTED";
        return String.format("RGB(%d,%d,%d)", color.getRed(), color.getGreen(), color.getBlue());
    }
}
