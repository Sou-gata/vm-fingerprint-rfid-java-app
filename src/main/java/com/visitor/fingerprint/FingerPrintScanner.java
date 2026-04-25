package com.visitor.fingerprint;

import com.visitor.model.VisitorTemplate;
import com.zkteco.biometric.FingerprintSensorErrorCode;
import com.zkteco.biometric.FingerprintSensorEx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

public class FingerPrintScanner {
    private static final Logger logger = LogManager.getLogger(FingerPrintScanner.class);
    private static long mhDevice = 0;
    private static long mhDB = 0;
    private static int fpWidth = 0;
    private static int fpHeight = 0;
    private static byte[] imgbuf = null;

    private static final Object lock = new Object();

    public static int byteArrayToInt(byte[] bytes) {
        if (bytes == null || bytes.length < 4)
            return 0;
        return (bytes[0] & 0xFF) |
                ((bytes[1] & 0xFF) << 8) |
                ((bytes[2] & 0xFF) << 16) |
                ((bytes[3] & 0xFF) << 24);
    }

    public static boolean initializeScanner() {
        synchronized (lock) {
            logger.info("Initializing ZKFinger SDK...");
            try {
                if (FingerprintSensorErrorCode.ZKFP_ERR_OK != FingerprintSensorEx.Init()) {
                    return false;
                }
            } catch (Throwable t) {
                logger.fatal("CRITICAL ERROR: Failed to load SDK native libraries (libzkfp). Unrecoverable error.", t);
                throw t; // It cannot be retried in the same JVM, rethrow to terminate
            }

            int deviceCount = FingerprintSensorEx.GetDeviceCount();
            if (deviceCount <= 0) {
                logger.error("No devices connected!");
                FingerprintSensorEx.Terminate();
                return false;
            }

            mhDevice = FingerprintSensorEx.OpenDevice(0);
            if (mhDevice == 0) {
                logger.error("Open device fail!");
                FingerprintSensorEx.Terminate();
                return false;
            }

            mhDB = FingerprintSensorEx.DBInit();
            if (mhDB == 0) {
                logger.error("Init DB fail!");
                FingerprintSensorEx.CloseDevice(mhDevice);
                FingerprintSensorEx.Terminate();
                return false;
            }

            byte[] paramValue = new byte[4];
            int[] size = new int[] { 4 };

            FingerprintSensorEx.GetParameters(mhDevice, 1, paramValue, size);
            fpWidth = byteArrayToInt(paramValue);

            size[0] = 4;
            FingerprintSensorEx.GetParameters(mhDevice, 2, paramValue, size);
            fpHeight = byteArrayToInt(paramValue);

            imgbuf = new byte[fpWidth * fpHeight];

            logger.info("Scanner initialized. Size: {}x{}", fpWidth, fpHeight);
            return true;
        }
    }

    public static void closeScanner() {
        synchronized (lock) {
            logger.info("Cleaning up and closing device...");
            if (mhDB != 0) {
                FingerprintSensorEx.DBFree(mhDB);
                mhDB = 0;
            }
            if (mhDevice != 0) {
                FingerprintSensorEx.CloseDevice(mhDevice);
                mhDevice = 0;
            }
            FingerprintSensorEx.Terminate();
            logger.info("Scanner closed successfully.");
        }
    }

    public static byte[] readFingerprint() {
        if (mhDevice == 0 || imgbuf == null) {
            logger.error("Error: Device is not initialized!");
            return null;
        }

        byte[] template = new byte[2048];
        int[] templateLen = new int[] { 2048 };

        logger.debug("Waiting for fingerprint scan...");

        while (true) {
            templateLen[0] = 2048;
            int ret;
            synchronized (lock) {
                ret = FingerprintSensorEx.AcquireFingerprint(mhDevice, imgbuf, template, templateLen);
            }

            if (ret == FingerprintSensorErrorCode.ZKFP_ERR_OK) {
                logger.info("Fingerprint captured successfully!");
                byte[] capturedTemplate = new byte[templateLen[0]];
                System.arraycopy(template, 0, capturedTemplate, 0, templateLen[0]);
                return capturedTemplate;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Scan interrupted.");
                break;
            }
        }
        return null;
    }

    public static long findMatchParallel(byte[] capturedTemplate, List<VisitorTemplate> candidates) {
        if (mhDB == 0 || capturedTemplate == null || candidates == null || candidates.isEmpty()) {
            return -1;
        }

        byte[] paddedCaptured = new byte[2048];
        System.arraycopy(capturedTemplate, 0, paddedCaptured, 0, Math.min(capturedTemplate.length, 2048));

        Optional<VisitorTemplate> matched = candidates.parallelStream()
                .filter(v -> {
                    byte[] paddedDB = new byte[2048];
                    byte[] dbTemplate = v.template();
                    System.arraycopy(dbTemplate, 0, paddedDB, 0, Math.min(dbTemplate.length, 2048));

                    int score;
                    synchronized (lock) {
                        score = FingerprintSensorEx.DBMatch(mhDB, paddedCaptured, paddedDB);
                    }
                    return score > 0;
                })
                .findFirst();

        return matched.map(VisitorTemplate::visitorId).orElse(-1L);
    }

    public static boolean matchFingerprint(byte[] template1, byte[] template2) {
        if (mhDB == 0 || template1 == null || template2 == null) {
            return false;
        }

        byte[] padded1 = new byte[2048];
        byte[] padded2 = new byte[2048];
        System.arraycopy(template1, 0, padded1, 0, Math.min(template1.length, 2048));
        System.arraycopy(template2, 0, padded2, 0, Math.min(template2.length, 2048));

        int matchScore;
        synchronized (lock) {
            matchScore = FingerprintSensorEx.DBMatch(mhDB, padded1, padded2);
        }

        logger.debug("Match Score: {}", matchScore);
        return matchScore > 0;
    }
}
