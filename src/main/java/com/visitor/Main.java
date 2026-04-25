package com.visitor;

import com.visitor.fingerprint.FingerPrintScanner;
import com.visitor.fingerprint.SocketServer;
import com.visitor.rfid.RfidService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class);

    public static void main(String[] args) {
        System.out.println("Starting Visitor Management RFID & FingerPrint Service...");

        // Start Fingerprint Service in a separate thread
        Thread fingerprintThread = new Thread(() -> {
            logger.info("Fingerprint Service Thread started.");
            while (!Thread.currentThread().isInterrupted()) {
                if (FingerPrintScanner.initializeScanner()) {
                    try {
                        new SocketServer();
                        logger.info("Fingerprint Socket Server is up and running.");
                        break; // Exit loop on success
                    } catch (Exception e) {
                        logger.error("Failed to start Fingerprint Socket Server: {}. Retrying in 10s...", e.getMessage());
                        FingerPrintScanner.closeScanner();
                    }
                } else {
                    logger.warn("Failed to initialize fingerprint scanner. Retrying in 5 seconds...");
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    logger.error("Fingerprint service retry interrupted.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        fingerprintThread.setName("Fingerprint-Service");
        fingerprintThread.start();

        // Start RFID Service in a separate thread
        Thread rfidThread = new Thread(() -> {
            logger.info("RFID Service Thread started.");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    RfidService rfidService = new RfidService();
                    Runtime.getRuntime().addShutdownHook(new Thread(rfidService::shutdown));
                    logger.info("RFID Service is up and running.");
                    break; // Exit loop on success
                } catch (Exception e) {
                    logger.error("Failed to start RFID Service: {}. Retrying in 10s...", e.getMessage());
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    logger.error("RFID service retry interrupted.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        rfidThread.setName("RFID-Service");
        rfidThread.start();

        // Global shutdown hook for cleaning up resources
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("System shutting down. Cleaning up...");
            FingerPrintScanner.closeScanner();
        }));

        logger.info("Main startup sequence completed. Services are initializing in the background.");
    }
}
