package com.visitor.rfid;

import javax.swing.*;
import java.awt.*;
import java.io.*;

public class SettingsWindow extends JDialog {
    private JTextField ipsField;
    private JTextField namesField;
    private String tenantId = "";
    private final String configPath = "config.ini";

    public SettingsWindow(Frame owner) {
        super(owner, "Settings", true);
        setSize(400, 160);
        setResizable(false);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        JPanel formPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        formPanel.add(new JLabel("IPs:"));
        ipsField = new JTextField();
        formPanel.add(ipsField);

        formPanel.add(new JLabel("Names:"));
        namesField = new JTextField();
        formPanel.add(namesField);

        add(formPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("Save");
        JButton cancelButton = new JButton("Cancel");

        saveButton.addActionListener(e -> saveSettings());
        cancelButton.addActionListener(e -> dispose());

        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);

        add(buttonPanel, BorderLayout.SOUTH);

        loadSettings();
    }

    private void loadSettings() {
        try (BufferedReader br = new BufferedReader(new FileReader(configPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("tenantId=")) {
                    tenantId = line.substring(line.indexOf("=") + 1).replace("\"", "").trim();
                } else if (line.trim().startsWith("ips=")) {
                    String jsonArray = line.substring(line.indexOf("=") + 1).trim();
                    jsonArray = jsonArray.replace("[", "").replace("]", "").replace("\"", "").trim();
                    ipsField.setText(jsonArray);
                } else if (line.trim().startsWith("names=")) {
                    String jsonArray = line.substring(line.indexOf("=") + 1).trim();
                    jsonArray = jsonArray.replace("[", "").replace("]", "").replace("\"", "").trim();
                    namesField.setText(jsonArray);
                }
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Error loading settings: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveSettings() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(configPath))) {
            bw.write("[Settings]");
            bw.newLine();
            bw.write("ips=" + formatArray(ipsField.getText()));
            bw.newLine();
            bw.write("names=" + formatArray(namesField.getText()));
            bw.newLine();
            bw.write("tenantId=\"" + tenantId + "\"");
            bw.newLine();

            JOptionPane.showMessageDialog(this,
                    "Settings saved successfully!\nPlease restart the application for changes to take effect.",
                    "Success", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Error saving settings: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private String formatArray(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "[]";
        }
        String[] items = input.split(",");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            sb.append("\"").append(items[i].trim()).append("\"");
            if (i < items.length - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}

