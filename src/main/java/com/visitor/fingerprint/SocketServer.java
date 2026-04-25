package com.visitor.fingerprint;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOServer;
import com.visitor.model.VisitorTemplate;
import com.visitor.model.VisitorModel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class SocketServer {
    private static final Logger logger = LogManager.getLogger(SocketServer.class);
    private static final String HOSTNAME = "0.0.0.0";
    private static final int PORT = 7071;
    private List<VisitorTemplate> fingerprintCache = new ArrayList<>();
    private SocketIOServer server;

    public SocketServer() {
        loadFingerprints();

        Configuration config = new Configuration();
        config.setHostname(HOSTNAME);
        config.setPort(PORT);

        config.setOrigin("*");

        server = new SocketIOServer(config);

        server.addEventListener("SCAN", String.class, (client, data, ackSender) -> {
            logger.debug("Client requested SCAN");
            String response = handleScan();
            client.sendEvent("SCAN_RESULT", response);
        });

        server.addEventListener("MATCH", String.class, (client, data, ackSender) -> {
            logger.debug("Client requested MATCH {}", data);
            String response = handleMatch(data);
            client.sendEvent("MATCH_RESULT", response);
        });

        server.addEventListener("RELOAD", String.class, (client, data, ackSender) -> {
            logger.info("Client requested RELOAD");
            loadFingerprints();
            String res = toJson(200, "RELOAD", "Cache reloaded", String.valueOf(fingerprintCache.size()));
            client.sendEvent("RELOAD_RESULT", res);
        });

        server.start();
        logger.info("Socket.io Server started on http://{}:{}", HOSTNAME, PORT);
    }

    private String handleScan() {
        byte[] fpRes = FingerPrintScanner.readFingerprint();

        if (fpRes == null) {
            return toJson(400, "SCAN", "Fingerprint scan failed or timed out.", "");
        }
        String base64Res = Base64.getEncoder().encodeToString(fpRes);
        return toJson(200, "SCAN", "Success", base64Res);
    }

    private String handleMatch(String fpData) {
//        byte[] fpRes = FingerPrintScanner.readFingerprint();
        byte[] fpRes = Base64.getDecoder().decode(fpData);
        if (fpRes == null) {
            return toJson(400, "MATCH", "Fingerprint scan failed or timed out.", "");
        }

        // Using your existing matching logic
        long matchedId = FingerPrintScanner.findMatchParallel(fpRes, fingerprintCache);
        logger.info("Match result: {}", matchedId);
//            long matchedId = 1L;
        if (matchedId != -1) {
            VisitorModel visitor = fetchVisitorById(matchedId);
            if (visitor == null) {
                return toJson(404, "MATCH", "Visitor record not found for matched id.", "");
            }
            return toJsonVisitor(200, "MATCH", "Match found", visitor);
        } else {
            return toJson(404, "MATCH", "No matching fingerprint found.", "");
        }
    }

    private void loadFingerprints() {
        logger.info("Socket Server: Loading fingerprints into memory cache...");
        List<VisitorTemplate> tempCache = new ArrayList<>();
        String query = "SELECT id,visitor_id, fp FROM visitor_fps_a1b2c3d4";

        try (Connection conn = DBConnect.getConnection();
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long id = rs.getLong("id");
                long visitorId = rs.getLong("visitor_id");
                String fpStr = rs.getString("fp");
                if (fpStr != null && !fpStr.isEmpty()) {
                    try {
                        byte[] fp = Base64.getDecoder().decode(fpStr);
                        tempCache.add(new VisitorTemplate(id, visitorId, fp));
                    } catch (IllegalArgumentException e) {
                        logger.error("Skipping invalid Base64 for ID: {}", id);
                    }
                }
            }
            this.fingerprintCache = tempCache;
            logger.info("Socket Server: Successfully loaded {} fingerprints.", fingerprintCache.size());
        } catch (SQLException e) {
            logger.error("Socket Server: Database error during cache load: {}", e.getMessage());
        }
    }

    private VisitorModel fetchVisitorById(long matchedId) {
//        String query = "SELECT id, name, email, visitor_contact, visitor_address, unit, city, visitor_image," +
//                " whome_to_meet, designation, document_type, document_number, document_image," +
//                " purpose, vehicle_number, in_time FROM visitors_a1b2c3d4 WHERE id = ?";
        String query = "SELECT id, name, email, visitor_contact, visitor_address, visitor_image," +
                " whome_to_meet, document_type, document_number, document_image," +
                " purpose, vehicle_number, in_time FROM visitors_a1b2c3d4 WHERE id = ?";
        try (Connection conn = DBConnect.getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setLong(1, matchedId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    VisitorModel v = new VisitorModel();
                    v.setId(rs.getLong("id"));
                    v.setName(rs.getString("name"));
                    v.setEmail(rs.getString("email"));
                    v.setVisitor_contact(rs.getString("visitor_contact"));
                    v.setVisitor_address(rs.getString("visitor_address"));
//                    v.setUnit(rs.getString("unit"));
//                    v.setCity(rs.getString("city"));
                    v.setVisitor_image(rs.getString("visitor_image"));
                    v.setWhome_to_meet(rs.getString("whome_to_meet"));
//                    v.setDesignation(rs.getString("designation"));
                    v.setDocument_type(rs.getString("document_type"));
                    v.setDocument_number(rs.getString("document_number"));
                    v.setDocument_image(rs.getString("document_image"));
                    v.setPurpose(rs.getString("purpose"));
                    v.setVehicle_number(rs.getString("vehicle_number"));
                    v.setIn_time(rs.getString("in_time"));
                    return v;
                }
            }
        } catch (SQLException e) {
            logger.error("Socket Server: DB error fetching visitor {}: {}", matchedId, e.getMessage());
        }
        return null;
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJsonVisitor(int statusCode, String command, String message, VisitorModel v) {
        String result = String.format(
                "{\"id\":%d,\"name\":\"%s\",\"email\":\"%s\",\"visitor_contact\":\"%s\"" +
                ",\"address\":\"%s\",\"unit\":\"%s\",\"city\":\"%s\",\"visitor_image\":\"%s\"" +
                ",\"whome_to_meet\":\"%s\",\"designation\":\"%s\",\"document_type\":\"%s\"" +
                ",\"document_number\":\"%s\",\"document_image\":\"%s\"" +
                ",\"purpose\":\"%s\",\"vehicle_number\":\"%s\",\"in_time\":\"%s\"}",
                v.getId(), safe(v.getName()), safe(v.getEmail()), safe(v.getVisitor_contact()),
                safe(v.getVisitor_address()), safe(v.getUnit()), safe(v.getCity()), safe(v.getVisitor_image()),
                safe(v.getWhome_to_meet()), safe(v.getDesignation()), safe(v.getDocument_type()),
                safe(v.getDocument_number()), safe(v.getDocument_image()),
                safe(v.getPurpose()), safe(v.getVehicle_number()), safe(v.getIn_time()));
        return String.format("{\"statusCode\": %d, \"command\": \"%s\", \"message\": \"%s\", \"result\": %s}",
                statusCode, command, safe(message), result);
    }

    private String toJson(int statusCode, String command, String message, String result) {
        return String.format("{\"statusCode\": %d, \"command\": \"%s\", \"message\": \"%s\", \"result\": \"%s\"}",
                statusCode,
                command,
                message.replace("\"", "\\\""),
                result.replace("\"", "\\\""));
    }

    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}