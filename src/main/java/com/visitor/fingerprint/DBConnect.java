package com.visitor.fingerprint;

import java.sql.*;
import com.visitor.rfid.RfidConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DBConnect {
    private static final Logger logger = LogManager.getLogger(DBConnect.class);
    public static Connection getConnection() {
        Connection connection = null;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(RfidConfig.dbUrl, RfidConfig.dbUser, RfidConfig.dbPassword);
        } catch (ClassNotFoundException e) {
            logger.error("MySQL JDBC Driver not found. Ensure the connector JAR is in dependencies.", e);
        } catch (SQLException e) {
            logger.error("Failed to connect to the database: {}", e.getMessage(), e);
        }
        return connection;
    }

    public static int getVisitorsCount() {
        int count = 0;
        String query = "SELECT COUNT(*) FROM visitors_a1b2c3d4";

        try (Connection connection = getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(query);
                ResultSet resultSet = preparedStatement.executeQuery()) {

            if (resultSet.next()) {
                count = resultSet.getInt(1);
            }

        } catch (SQLException e) {
            logger.error("Error fetching visitors count: {}", e.getMessage(), e);
        }

        return count;
    }


}
