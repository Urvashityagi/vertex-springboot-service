package com.example.studentsystem.service;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import org.springframework.stereotype.Component;

import javax.mail.*;
import javax.mail.internet.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Properties;

@Component
public class AppInstallerVerticle extends AbstractVerticle {

    private JDBCClient jdbcClient;

    @Override
    public void start() {
        // Configure the JDBC client
        JsonObject config = new JsonObject()
                .put("url", "jdbc:h2:mem:testdb") // Persistent database
                .put("driver_class", "org.h2.Driver")
                .put("user", "root")
                .put("password", "");

        jdbcClient = JDBCClient.createShared(vertx, config);

        // Start the installation process
        startInstallationProcess();
    }

    private void startInstallationProcess() {
        jdbcClient.getConnection(connectionResult -> {
            if (connectionResult.failed()) {
                System.err.println("Failed to connect to the database: " + connectionResult.cause());
                return;
            }

            SQLConnection connection = connectionResult.result();
            connection.query("SELECT * FROM APP WHERE INSTALLED = FALSE OR LATEST_VERSION > VERSION", queryResult -> {
                if (queryResult.failed()) {
                    System.err.println("Failed to query apps: " + queryResult.cause());
                    connection.close();
                    return;
                }

                queryResult.result().getRows().forEach(row -> {
                    String appName = row.getString("NAME");
                    Long appId = row.getLong("ID");
                    String currentVersion = row.getString("VERSION");
                    String latestVersion = row.getString("LATEST_VERSION");

                    System.out.println("App ID and name: " + appId + ", " + appName);

                    if (!row.getBoolean("INSTALLED")) {
                        System.out.println("Scheduling app for installation: " + appName);
                        installApp(appId, appName, 0);
                    } else if (latestVersion != null && !latestVersion.equals(currentVersion)) {
                        System.out.println("Scheduling app for update: " + appName + " from " + currentVersion + " to " + latestVersion);
                        updateApp(appId, appName, currentVersion, latestVersion, connection, 0);
                    }
                });

                rescheduleAppsInErrorState(connection);

                connection.close();
            });
        });
    }

    private void installApp(Long appId, String appName, int retryCount) {
        System.out.println("Installing app: " + appName);

        // Get a fresh connection for each operation
        jdbcClient.getConnection(connectionResult -> {
            if (connectionResult.failed()) {
                System.err.println("Failed to connect to the database: " + connectionResult.cause());
                return;
            }

            SQLConnection connection = connectionResult.result();

            // Log state transition to PICKEDUP
            logStateTransition(appId, "PICKEDUP", connection);

            vertx.setTimer(2000, timerId -> {
                if (Math.random() < 0.5) { // Simulating random failure
                    System.err.println("Installation failed for app: " + appName);

                    if (retryCount < 3) {
                        // Log state transition to ERROR
                        logStateTransition(appId, "ERROR", connection);
                        System.out.println("Retrying installation for app: " + appName);
                        installApp(appId, appName, retryCount + 1);
                    } else {
                        // Send failure notification after max retries
                        sendFailureNotification(appId, appName);
                    }
                    connection.close(); // Close the connection after failure handling
                } else {
                    System.out.println("Installation complete for app: " + appName);

                    // TEST: Trigger notification even on success
//                    sendFailureNotification(appId, appName); // Remove after testing

                    // Log state transition to COMPLETED
                    logStateTransition(appId, "COMPLETED", connection);

                    // Update the app as installed
                    connection.updateWithParams(
                            "UPDATE APP SET INSTALLED = TRUE WHERE ID = ?",
                            new io.vertx.core.json.JsonArray().add(appId),
                            updateResult -> {
                                if (updateResult.failed()) {
                                    System.err.println("Failed to update app status: " + updateResult.cause());
                                } else {
                                    System.out.println("App marked as installed in the database: " + appName);
                                }
                                connection.close(); // Close the connection after updating
                            }
                    );
                }
            });
        });
    }
    private void updateApp(Long appId, String appName, String currentVersion, String latestVersion, SQLConnection connection, int retryCount) {
        System.out.println("Updating app: " + appName + " from version " + currentVersion + " to " + latestVersion);

        // Log state transition to UPDATING
        logStateTransition(appId, "UPDATING", connection);

        vertx.setTimer(2000, timerId -> {
            if (Math.random() < 0.5) { // Simulating random failure
                System.err.println("Update failed for app: " + appName);

                if (retryCount < 3) {
                    logStateTransition(appId, "ERROR", connection);
                    System.out.println("Retrying update for app: " + appName);
                    updateApp(appId, appName, currentVersion, latestVersion, connection, retryCount + 1);
                } else {
                    sendFailureNotification(appId, appName);
                }
            } else {
                System.out.println("Update complete for app: " + appName);

                // Log state transition to COMPLETED
                logStateTransition(appId, "COMPLETED", connection);

                // Update the app's version in the database
                connection.updateWithParams(
                        "UPDATE APP SET VERSION = ?, LATEST_VERSION = NULL WHERE ID = ?",
                        new io.vertx.core.json.JsonArray().add(latestVersion).add(appId),
                        updateResult -> {
                            if (updateResult.failed()) {
                                System.err.println("Failed to update app version: " + updateResult.cause());
                            } else {
                                System.out.println("App updated to version " + latestVersion + " in the database: " + appName);
                            }
                        }
                );
            }
        });
    }

    private void logStateTransition(Long appId, String newState, SQLConnection connection) {
        Timestamp timestamp = Timestamp.valueOf(LocalDateTime.now());

        System.out.println("App ID: " + appId + " transitioned to state: " + newState + " at " + timestamp);

        connection.updateWithParams(
                "UPDATE APP SET STATE = ?, LAST_STATE_UPDATE = ? WHERE ID = ?",
                new io.vertx.core.json.JsonArray().add(newState).add(timestamp).add(appId),
                updateResult -> {
                    if (updateResult.failed()) {
                        System.err.println("Failed to log state transition: " + updateResult.cause());
                    }
                }
        );
    }

    private void rescheduleAppsInErrorState(SQLConnection connection) {
        connection.query("SELECT * FROM APP WHERE STATE = 'ERROR'", queryResult -> {
            if (queryResult.failed()) {
                System.err.println("Failed to query apps in ERROR state: " + queryResult.cause());
                return;
            }

            // Process each app in ERROR state
            queryResult.result().getRows().forEach(row -> {
                Long appId = row.getLong("ID");
                String appName = row.getString("NAME");
                String currentVersion = row.getString("VERSION");
                String latestVersion = row.getString("LATEST_VERSION");

                System.out.println("Rescheduling app for retry: " + appName);

                if (latestVersion != null && !latestVersion.equals(currentVersion)) {
                    // If there's a version update, reschedule the update
                    updateApp(appId, appName, currentVersion, latestVersion, connection, 0);
                } else {
                    // If no version update, reschedule the installation
                    installApp(appId, appName, 0);
                }
            });
        });
    }

    private void sendFailureNotification(Long appId, String appName) {
        System.out.println("Sending failure notification for app: " + appName);

        Properties props = new Properties();
        props.put("mail.smtp.host", "sandbox.smtp.mailtrap.io");
        props.put("mail.smtp.port", "2525");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("080e06b854d0d5", "61623611f64a8c");
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress("noreply@example.com"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("admin@example.com"));
            message.setSubject("App Installation Failed");
            message.setText("The app '" + appName + "' (ID: " + appId + ") failed to install after 3 attempts.");

            Transport.send(message);

            System.out.println("Failure notification sent for app: " + appName);
        } catch (MessagingException e) {
            System.err.println("Failed to send failure notification: " + e.getMessage());
        }
    }



    @Override
    public void stop() {
        if (jdbcClient != null) {
            jdbcClient.close();
        }
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new AppInstallerVerticle(), result -> {
            if (result.succeeded()) {
                System.out.println("AppInstallerVerticle deployed successfully!");
            } else {
                System.err.println("Failed to deploy AppInstallerVerticle: " + result.cause());
            }
        });
    }
}
