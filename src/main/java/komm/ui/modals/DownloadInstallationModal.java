package komm.ui.modals;

import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import komm.App;
import komm.ui.utils.FileChooserUtil;
import java.io.File;
import java.util.UUID;

public class DownloadInstallationModal extends VBox {

	private Button downloadZipButton;
	private ProgressBar progressBar;
	private Label statusLabel;
	private UUID installationId;

	public DownloadInstallationModal(UUID installationId) {
		this.installationId = installationId;
		this.setAlignment(Pos.TOP_CENTER);
		this.setStyle("-fx-background-color: -color-bg-overlay; -fx-border-radius: 10px; -fx-background-radius: 10px; -fx-border-color: -color-accent-7; -fx-border-width: 1px;");
		this.setMaxSize(600, 260);
		this.setMinSize(600, 260);
		this.setPrefSize(600, 260);
		this.setSpacing(15);
		this.setPadding(new Insets(0, 0, 20, 0));

		// Header with close button
		HBox headerBox = createHeader();

		// Content
		VBox contentBox = createContent();

		// Progress section
		VBox progressBox = createProgressBox();

		// Buttons
		HBox buttonBox = createButtonBox();

		this.getChildren().addAll(headerBox, contentBox, progressBox, buttonBox);
	}

	private HBox createHeader() {
		HBox headerBox = new HBox();
		headerBox.setAlignment(Pos.CENTER_RIGHT);
		headerBox.setPadding(new Insets(10, 10, 0, 0));
		Region headerFillerRegion = new Region();
		Button closeButton = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
		closeButton.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
		closeButton.setOnAction(event -> App.closeModal());
		headerBox.getChildren().addAll(headerFillerRegion, closeButton);
		HBox.setHgrow(headerFillerRegion, Priority.ALWAYS);
		return headerBox;
	}

	private VBox createContent() {
		VBox contentBox = new VBox(8);
		contentBox.setAlignment(Pos.CENTER);
		contentBox.setPadding(new Insets(0, 40, 0, 40));

		Label titleLabel = new Label("Download Installation Files");
		titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

		Label descriptionLabel = new Label("Choose what you want to download for this installation:");
		descriptionLabel.setStyle("-fx-text-fill: -color-fg-muted;");
		descriptionLabel.setWrapText(true);
		descriptionLabel.setMaxWidth(500);
		descriptionLabel.setAlignment(Pos.CENTER);

		contentBox.getChildren().addAll(titleLabel, descriptionLabel);

		return contentBox;
	}

	private VBox createProgressBox() {
		VBox progressBox = new VBox(8);
		progressBox.setAlignment(Pos.CENTER);
		progressBox.setPadding(new Insets(0, 40, 0, 40));
		progressBox.setMinHeight(50);
		progressBox.setPrefHeight(50);

		progressBar = new ProgressBar();
		progressBar.setPrefWidth(520);
		progressBar.setPrefHeight(8);
		progressBar.setVisible(false);

		statusLabel = new Label();
		statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
		statusLabel.setAlignment(Pos.CENTER);
		statusLabel.setMaxWidth(520);
		statusLabel.setVisible(false);

		progressBox.getChildren().addAll(progressBar, statusLabel);

		return progressBox;
	}

	private HBox createButtonBox() {
		HBox buttonBox = new HBox(15);
		buttonBox.setAlignment(Pos.CENTER);
		buttonBox.setPadding(new Insets(0, 20, 0, 20));

		downloadZipButton = new Button("Installation ZIP", new FontIcon(MaterialDesignD.DOWNLOAD));
		downloadZipButton.setFocusTraversable(false);
		downloadZipButton.setOnAction(e -> handleDownloadZip());

		buttonBox.getChildren().addAll(downloadZipButton);

		return buttonBox;
	}
	
	private void handleDownloadZip() {
		// Let user choose save location
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Save Installation ZIP");
		fileChooser.setInitialFileName("installation.zip");
		fileChooser.getExtensionFilters().add(
			new FileChooser.ExtensionFilter("ZIP Files", "*.zip")
		);
		
		File file = FileChooserUtil.showSaveDialog(fileChooser, this.getScene().getWindow());
		if (file == null) {
			return; // User cancelled
		}

		// Start downloading
		Platform.runLater(() -> {
			setDownloadingState(true, "Starting download...");
			progressBar.setProgress(0.0);
		});
	}

	private void setDownloadingState(boolean downloading, String message) {
		App.getModalPane().setPersistent(downloading);
		downloadZipButton.setDisable(downloading);
		progressBar.setVisible(downloading);
		statusLabel.setVisible(downloading);
		
		if (downloading) {
			statusLabel.setText(message);
		}
	}

	private void updateStatus(String message, boolean success) {
		statusLabel.setText(message);
		statusLabel.setStyle(success ? 
			"-fx-text-fill: -color-success-fg; -fx-font-size: 12px;" : 
			"-fx-text-fill: -color-danger-fg; -fx-font-size: 12px;");
		statusLabel.setVisible(true);
	}
}