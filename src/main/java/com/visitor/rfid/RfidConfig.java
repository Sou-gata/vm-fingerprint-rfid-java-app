package com.visitor.rfid;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class RfidConfig {

    public static String tenantId = "UNKNOWN_TENANT";
    public static String endPoint  = "";
    public static String dbUrl = "";
    public static String dbUser = "";
    public static String dbPassword = "";

    public static class ReaderDevice {
        public String ip;
        public String name;

        public ReaderDevice(String ip, String name) {
            this.ip = ip;
            this.name = name;
        }
    }

    public static List<ReaderDevice> loadDevicesFromIni(String filePath) {
        List<String> ipList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        List<ReaderDevice> devices = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("tenantId=")) {
                    tenantId = line.substring(line.indexOf("=") + 1).replace("\"", "").trim();
                } else if (line.trim().startsWith("endPoint=")) {
                    endPoint = line.substring(line.indexOf("=") + 1).replace("\"", "").trim();
                } else if (line.trim().startsWith("dbUrl=")) {
                    dbUrl = line.substring(line.indexOf("=") + 1).replace("\"", "").trim();
                } else if (line.trim().startsWith("dbUser=")) {
                    dbUser = line.substring(line.indexOf("=") + 1).replace("\"", "").trim();
                } else if (line.trim().startsWith("dbPassword=")) {
                    dbPassword = line.substring(line.indexOf("=") + 1).replace("\"", "").trim();
                }
                else if (line.trim().startsWith("ips=")) {
                    ipList = parseArray(line);
                }
                else if (line.trim().startsWith("names=")) {
                    nameList = parseArray(line);
                }
            }

            for (int i = 0; i < ipList.size(); i++) {
                String ip = ipList.get(i);
                String name = (i < nameList.size()) ? nameList.get(i) : "Unknown Reader";
                devices.add(new ReaderDevice(ip, name));
            }
        } catch (Exception e) {
//            System.err.println("Could not load config.ini: " + e.getMessage());
        }
        return devices;
    }

    private static List<String> parseArray(String line) {
        List<String> list = new ArrayList<>();
        String jsonArray = line.substring(line.indexOf("=") + 1).trim();
        jsonArray = jsonArray.replace("[", "").replace("]", "").replace("\"", "").trim();
        if (!jsonArray.isEmpty()) {
            String[] items = jsonArray.split(",");
            for (String item : items) {
                list.add(item.trim());
            }
        }
        return list;
    }
}
