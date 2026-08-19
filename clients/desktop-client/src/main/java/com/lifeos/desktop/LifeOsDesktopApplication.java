package com.lifeos.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** JavaFX shell with a bounded, memory-only password authentication boundary. */
public final class LifeOsDesktopApplication extends Application {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final URI API_BASE = URI.create(System.getenv().getOrDefault("LIFEOS_API_BASE_URL", "http://localhost:8080"));
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private Stage stage;
    private String accessToken;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("LifeOS");
        showAuthentication();
    }

    private void showAuthentication() {
        TextField email = new TextField();
        email.setPromptText("Email");
        email.setAccessibleText("Email address");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        password.setAccessibleText("Password");
        TextField displayName = new TextField();
        displayName.setPromptText("Display name");
        displayName.setAccessibleText("Display name");
        displayName.setVisible(false);
        displayName.setManaged(false);
        CheckBox register = new CheckBox("Create an account");
        register.setAccessibleText("Switch between sign in and account registration");
        Label title = new Label("Sign in to LifeOS");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label privacy = new Label("Your access token stays in memory and is never written to desktop storage.");
        privacy.setWrapText(true);
        Label status = new Label();
        status.setWrapText(true);
        status.setAccessibleText("Authentication status");
        Button submit = new Button("Sign in");
        submit.setDefaultButton(true);
        submit.setAccessibleText("Submit authentication request");

        register.selectedProperty().addListener((observable, oldValue, selected) -> {
            title.setText(selected ? "Create your LifeOS account" : "Sign in to LifeOS");
            submit.setText(selected ? "Create account" : "Sign in");
            displayName.setVisible(selected);
            displayName.setManaged(selected);
            status.setText("");
        });
        submit.setOnAction(event -> {
            String emailValue = email.getText().strip();
            String passwordValue = password.getText();
            String displayNameValue = displayName.getText().strip();
            if (emailValue.isBlank() || passwordValue.isBlank() || (register.isSelected() && displayNameValue.isBlank())) {
                status.setText("Enter the required fields and try again.");
                return;
            }
            submit.setDisable(true);
            status.setText("Contacting LifeOS…");
            authenticate(register.isSelected(), emailValue, displayNameValue, passwordValue)
                    .whenComplete((token, failure) -> Platform.runLater(() -> {
                        submit.setDisable(false);
                        password.clear();
                        if (failure != null) {
                            status.setText("We could not complete that request. Check your details and try again.");
                        } else {
                            accessToken = token;
                            showWorkspace();
                        }
                    }));
        });

        VBox card = new VBox(12, title, privacy, email, displayName, password, register, status, submit);
        card.setPadding(new Insets(32));
        card.setMaxWidth(460);
        VBox centered = new VBox(card);
        centered.setAlignment(Pos.CENTER);
        centered.setPadding(new Insets(24));
        stage.setScene(new Scene(centered, 760, 480));
        stage.show();
    }

    private CompletableFuture<String> authenticate(boolean registering, String email, String displayName, String password) {
        String accountBody = "{\"email\":\"" + jsonEscape(email) + "\",\"displayName\":\""
                + jsonEscape(displayName) + "\",\"password\":\"" + jsonEscape(password) + "\"}";
        String loginBody = "{\"email\":\"" + jsonEscape(email) + "\",\"password\":\"" + jsonEscape(password) + "\"}";
        HttpRequest request = jsonRequest(registering ? "/api/v1/accounts" : "/api/v1/auth/login", registering ? accountBody : loginBody,
                registering ? UUID.randomUUID().toString() : null);
        return send(request).thenCompose(response -> {
            requireSuccess(response);
            return registering
                    ? send(jsonRequest("/api/v1/auth/login", loginBody, null)).thenApply(responseAfterRegistration -> {
                        requireSuccess(responseAfterRegistration);
                        return extractAccessToken(responseAfterRegistration.body());
                    })
                    : CompletableFuture.completedFuture(extractAccessToken(response.body()));
        });
    }

    private static CompletableFuture<HttpResponse<String>> send(HttpRequest request) {
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(8, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static HttpRequest jsonRequest(String path, String body, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(API_BASE.resolve(path))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder.build();
    }

    private static void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("authentication failed");
        }
    }

    static String extractAccessToken(String responseBody) {
        Matcher matcher = ACCESS_TOKEN.matcher(responseBody == null ? "" : responseBody);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            throw new IllegalStateException("authentication response incomplete");
        }
        return matcher.group(1);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private void showWorkspace() {
        ListView<String> navigation = new ListView<>(FXCollections.observableArrayList(
                "Home", "Plan", "Calendar", "Money", "Vault", "Assistant", "Sessions", "Settings"));
        navigation.setAccessibleText("Primary navigation");
        navigation.setPrefWidth(150);
        navigation.getSelectionModel().select(0);

        Label sessionStatus = new Label("Authenticated · token held in memory");
        sessionStatus.setAccessibleText("Authenticated session status");
        Button signOut = new Button("Sign out");
        signOut.setAccessibleText("Sign out and clear the in-memory token");
        signOut.setOnAction(event -> {
            accessToken = null;
            showAuthentication();
        });
        HBox top = new HBox(12, sessionStatus, signOut);
        HBox.setHgrow(sessionStatus, Priority.ALWAYS);
        top.setAlignment(Pos.CENTER_RIGHT);
        top.setPadding(new Insets(12, 16, 4, 16));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setLeft(navigation);
        root.setCenter(destination("Home", description("Home")));
        navigation.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            if (selected != null) root.setCenter(destination(selected, description(selected)));
        });
        BorderPane.setMargin(navigation, new Insets(16, 0, 16, 16));
        root.setPadding(new Insets(8, 24, 24, 8));
        stage.setScene(new Scene(root, 760, 480));
        stage.show();
    }

    private VBox destination(String titleText, String descriptionText) {
        Label title = new Label(titleText);
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label description = new Label(descriptionText);
        description.setWrapText(true);
        Label status = new Label("Loading bounded owner-scoped data…");
        status.setWrapText(true);
        status.setAccessibleText("Destination data status");
        ListView<String> records = new ListView<>();
        records.setAccessibleText(titleText + " owner-scoped records");
        records.setPlaceholder(new Label("No owner-scoped records returned."));
        Button refresh = new Button("Refresh");
        refresh.setAccessibleText("Refresh " + titleText + " records");
        refresh.setOnAction(event -> loadDestination(titleText, records, status));
        HBox heading = new HBox(12, title, refresh);
        if ("Sessions".equals(titleText)) {
            Button confirmAction = new Button("Confirm action item");
            confirmAction.setAccessibleText("Confirm a session action item as a task");
            confirmAction.setOnAction(event -> confirmSessionTask(records, status));
            heading.getChildren().add(confirmAction);
        }
        HBox.setHgrow(title, Priority.ALWAYS);
        VBox content = new VBox(12, heading, description, status, records);
        content.setPadding(new Insets(24));
        VBox.setVgrow(records, Priority.ALWAYS);
        loadDestination(titleText, records, status);
        return content;
    }

    private void loadDestination(String title, ListView<String> records, Label status) {
        if (accessToken == null || accessToken.isBlank()) {
            status.setText("Signed out · no private data is shown");
            records.getItems().clear();
            return;
        }
        String path = switch (title) {
            case "Home" -> "/api/v1/analytics/dashboard?periodDays=30";
            case "Plan" -> "/api/v1/tasks?limit=30";
            case "Calendar" -> "/api/v1/calendar/events?limit=30";
            case "Money" -> "/api/v1/finance/transactions?page=0&pageSize=30";
            case "Vault" -> "/api/v1/documents/search?q=lifeos&pageSize=20";
            case "Sessions" -> "/api/v1/media/sessions?limit=30";
            default -> null;
        };
        if (path == null) {
            status.setText("Ready · use the bounded authenticated API for this surface");
            records.getItems().setAll("No private records loaded yet");
            return;
        }
        status.setText("Loading bounded owner-scoped data…");
        sendDestination(HttpRequest.newBuilder(API_BASE.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build()).whenComplete((response, failure) -> Platform.runLater(() -> {
            if (failure != null || response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                status.setText("Source unavailable · no private data was shown");
                records.getItems().clear();
                return;
            }
            records.getItems().setAll(summarizeRecords(response.body()));
            status.setText("Source loaded · bounded owner-scoped read");
        }));
    }

    private static CompletableFuture<HttpResponse<String>> sendDestination(HttpRequest request) {
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void confirmSessionTask(ListView<String> records, Label status) {
        if (accessToken == null || accessToken.isBlank()) {
            status.setText("Signed out · no private data was changed");
            return;
        }
        TextInputDialog sessionDialog = new TextInputDialog();
        sessionDialog.setTitle("Confirm action item");
        sessionDialog.setHeaderText("Enter the ended session UUID");
        sessionDialog.setContentText("Session ID:");
        var session = sessionDialog.showAndWait();
        if (session.isEmpty() || session.get().isBlank()) return;
        final UUID sessionId;
        try {
            sessionId = UUID.fromString(session.get().strip());
        } catch (IllegalArgumentException exception) {
            status.setText("Enter a valid session UUID.");
            return;
        }
        TextInputDialog versionDialog = new TextInputDialog("0");
        versionDialog.setTitle("Confirm action item");
        versionDialog.setHeaderText("Enter the post-session artifact version");
        versionDialog.setContentText("Artifact version:");
        var version = versionDialog.showAndWait();
        if (version.isEmpty() || !version.get().matches("\\d+")) return;
        TextInputDialog actionDialog = new TextInputDialog();
        actionDialog.setTitle("Confirm action item");
        actionDialog.setHeaderText("Enter the exact extracted action text");
        actionDialog.setContentText("Action item:");
        var action = actionDialog.showAndWait();
        if (action.isEmpty() || action.get().isBlank()) return;
        String body = "{\"actionItem\":\"" + jsonEscape(action.get().strip()) + "\",\"priority\":3,\"dueAt\":null}";
        HttpRequest request = HttpRequest.newBuilder(API_BASE.resolve(
                        "/api/v1/media/sessions/" + sessionId + "/post-session/tasks"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", "desktop-" + UUID.randomUUID())
                .header("If-Match", "\"" + version.get() + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        status.setText("Confirming action item…");
        sendDestination(request).whenComplete((response, failure) -> Platform.runLater(() -> {
            if (failure != null || response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                status.setText("The action item could not be confirmed.");
                return;
            }
            status.setText("Follow-up task created.");
            loadDestination("Sessions", records, status);
        }));
    }

    private static java.util.List<String> summarizeRecords(String responseBody) {
        String body = responseBody == null ? "" : responseBody;
        java.util.List<String> values = new java.util.ArrayList<>();
        Matcher matcher = Pattern.compile("\\\"(?:title|name|key|category|subject)\\\"\\s*:\\s*\\\"([^\\\"]{1,160})\\\"").matcher(body);
        while (matcher.find() && values.size() < 30) {
            values.add(matcher.group(1));
        }
        if (values.isEmpty() && !body.isBlank()) {
            values.add("Source returned a bounded response");
        }
        return values;
    }

    private static String description(String destination) {
        return switch (destination) {
            case "Home" -> "Priorities, reminders, and measured signals.";
            case "Plan" -> "Tasks, goals, habits, routines, and milestones.";
            case "Calendar" -> "Events, focus blocks, conflicts, and reminders.";
            case "Money" -> "Budgets, transactions, insights, and forecasts.";
            case "Vault" -> "Private documents, search, and proof status.";
            case "Assistant" -> "Grounded answers and confirmed actions.";
            case "Sessions" -> "Scheduled sessions, timers, and recordings.";
            case "Settings" -> "Profile, privacy, AI preferences, and devices.";
            default -> "Destination unavailable.";
        };
    }

    public static void main(String[] args) {
        launch(args);
    }
}
