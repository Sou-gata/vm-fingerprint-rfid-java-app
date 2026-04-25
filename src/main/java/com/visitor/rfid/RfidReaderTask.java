package com.visitor.rfid;

import java.util.List;
import org.apache.commons.codec.binary.Hex;
import com.marktrace.api.MRReaderAPI;
import com.marktrace.uhf.MR6100API;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RfidReaderTask implements Runnable {
    private static final Logger logger = LogManager.getLogger(RfidReaderTask.class);

    // Callback interface to communicate with the GUI
    public interface TagListener {
        void onTagRead(String ip, String tagData);

        void onLog(String ip, String message);

        void onConnectionStateChange(String ip, String stateMessage, java.awt.Color color);
    }

    private String ip;
    private int port = 100;
    private TagListener listener;
    private volatile boolean running = true;

    public RfidReaderTask(String ip, TagListener listener) {
        this.ip = ip;
        this.listener = listener;
    }

    public void stopReader() {
        this.running = false;
    }

    @Override
    public void run() {
        MR6100API m6 = new MR6100API();
        while (running) {
            try {
                listener.onLog(ip, "Attempting to connect...");
                listener.onConnectionStateChange(ip, "Connecting...", java.awt.Color.ORANGE);

                if (m6.TcpConnectReader(ip, port)) {
                    listener.onLog(ip, "SUCCESS: Connected.");
                    listener.onConnectionStateChange(ip, "Connected", new java.awt.Color(0, 153, 0)); // Darker Green

                    // Inner loop: Keep reading tags while connected and active
                    while (running) {
                        m6.ClearIDBuffer();
                        byte[] response = m6.Gen2MultiTagInventory();

                        if (response != null && response.length > 0) {
                            if (response[0] == MRReaderAPI.ERR_NONE) {
                                List<byte[]> tagList = m6.GetTagData();
                                if (tagList != null && !tagList.isEmpty()) {
                                    for (byte[] tagBuffer : tagList) {
                                        int len = tagBuffer[0];
                                        byte[] data = new byte[len - 1];
                                        System.arraycopy(tagBuffer, 2, data, 0, data.length);
                                        for (int i = 0, j = data.length - 1; i < j; i++, j--) {
                                            byte tmp = data[i];
                                            data[i] = data[j];
                                            data[j] = tmp;
                                        }
                                        String hexEPC = Hex.encodeHexString(data).toUpperCase();
                                        String textData = hexToAscii(hexEPC);

                                        listener.onLog(ip, "RFID Tag Read - EPC: " + hexEPC + ", ASCII: " + textData);

                                        listener.onTagRead(ip, textData);
                                    }
                                }
                            } else {
                            }
                        } else {
                            listener.onLog(ip, "Connection appears dropped. Initiating reconnect cycle.");
                            break;
                        }
                        Thread.sleep(200);
                    }
                } else {
                    listener.onLog(ip, "FAILURE: Could not connect.");
                }
            } catch (Exception e) {
                listener.onLog(ip, "Error: " + e.getMessage());
                logger.error("Error in RFID reader task [{}]: {}", ip, e.getMessage(), e);
            } finally {
                // Ensure connection is closed before retrying
                try {
                    m6.TcpCloseConnect();
                } catch (Exception ignored) {
                }
            }

            // Retry logic with countdown if still running
            if (running) {
                listener.onLog(ip, "Scheduling reconnect retry in 60 seconds.");
                for (int i = 60; i > 0 && running; i--) {
                    listener.onConnectionStateChange(ip, "Retrying in " + i + "s", java.awt.Color.RED);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        listener.onLog(ip, "Reader Thread Stopped.");
    }

    private String hexToAscii(String hexStr) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < hexStr.length(); i += 2) {
            String str = hexStr.substring(i, i + 2);
            if (str.equals("00"))
                continue;
            try {
                int decimal = Integer.parseInt(str, 16);
                if (decimal >= 32 && decimal <= 126) {
                    output.append((char) decimal);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return output.toString();
    }
}
