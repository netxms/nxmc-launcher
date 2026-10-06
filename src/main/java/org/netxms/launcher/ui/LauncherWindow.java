package org.netxms.launcher.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.netxms.launcher.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class LauncherWindow implements LauncherView {
    private static final String CONNECT = "Connect";
    private static final String CANCEL = "Cancel";
    private final Display display;
    private final ServerRegistry registry;
    private final PackageManager packages;
    private final LauncherSettings settings;
    private final ConnectFlow.ManifestSource manifests;
    private Shell shell;
    private Combo serverCombo;
    private Text loginField;
    private Text passwordField;
    private Button connectButton;
    private Button settingsButton;
    private Label statusLabel;
    private ConnectAction connectAction;
    private String autoFilledLogin = "";
    private DownloadEta eta = new DownloadEta();
    private AtomicBoolean attempt;

    public LauncherWindow(Display display, ServerRegistry registry, PackageManager packages, LauncherSettings settings, ConnectFlow.ManifestSource manifests) {
        this.display = display;
        this.registry = registry;
        this.packages = packages;
        this.settings = settings;
        this.manifests = manifests;
    }

    static String logoBase(RGB background) {
        return DisplayFormat.isDarkColor(background) ? "logo-dark" : "logo";
    }

    static ImageDataProvider logoProvider(ImageData normal, ImageData retina) {
        return zoom -> (zoom == 100) ? normal : (zoom == 200) ? retina : null;
    }

    private static ImageData readImageData(String name) {
        try (InputStream stream = LauncherWindow.class.getResourceAsStream(name)) {
            return (stream != null) ? new ImageData(stream) : null;
        } catch (IOException | SWTException e) {
            return null;
        }
    }

    public void setConnectAction(ConnectAction connectAction) {
        this.connectAction = connectAction;
    }

    public void open() {
        createShell();
        fillServers();
        fillLoginFromSelection();
        registry.loadWarning().ifPresent(this::showWarning);

        shell.open();
        askAboutUpdateChecks();
        focusFirstFieldNeedingInput();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }

    private void askAboutUpdateChecks() {
        if (settings.checkForUpdates().isPresent()) {
            return;
        }

        settings.setCheckForUpdates(Dialogs.confirm(shell, DisplayFormat.updateCheckQuestion(manifests.host())));
        try {
            settings.save();
        } catch (IOException e) {
            showWarning("Cannot save the launcher settings to " + settings.file() + ": " + e + "\n\nThe question about checking for newer nxmc builds is asked again next time.");
        }
    }

    private void createShell() {
        // no resize: the window holds three fields and a status line, and every state it shows fits
        shell = new Shell(display, SWT.TITLE | SWT.CLOSE | SWT.MIN);
        shell.setText("NetXMS - Connect to Server");
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        layout.horizontalSpacing = 20;
        shell.setLayout(layout);

        createLogo(shell);
        createForm(shell);
        createFooter(shell);

        shell.setDefaultButton(connectButton);
        shell.pack();
        center();
    }

    private void createLogo(Composite parent) {
        Image image = loadLogo();
        Label label = new Label(parent, SWT.NONE);
        label.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, false, true));
        if (image == null) {
            return;
        }

        label.setImage(image);
        label.addDisposeListener(e -> image.dispose());
    }

    private Image loadLogo() {
        String base = logoBase(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB());
        ImageData normal = readImageData(base + ".png");
        if (normal == null) {
            return null;
        }

        ImageData retina = readImageData(base + "@2x.png");
        return new Image(display, logoProvider(normal, retina));
    }

    private void createForm(Composite parent) {
        Composite form = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout();
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 8;
        form.setLayout(layout);
        form.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(form, SWT.NONE).setText("Server");
        serverCombo = new Combo(form, SWT.DROP_DOWN);
        GridData comboData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        // the widest field is what sets the window's width: the status line below spans the same
        // inner width, and a longer failure sentence fits before it has to clip into the tooltip
        comboData.widthHint = 280;
        serverCombo.setLayoutData(comboData);
        serverCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                fillLoginFromSelection();
                focusFirstFieldNeedingInput();
            }
        });

        new Label(form, SWT.NONE).setText("Login");
        loginField = new Text(form, SWT.BORDER);
        loginField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(form, SWT.NONE).setText("Password");
        passwordField = new Text(form, SWT.BORDER | SWT.PASSWORD);
        passwordField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createFooter(Composite parent) {
        Composite footer = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.marginTop = 10;
        footer.setLayout(layout);
        GridData footerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        footerData.horizontalSpan = 2;
        footer.setLayoutData(footerData);

        statusLabel = new Label(footer, SWT.NONE);
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusData.horizontalSpan = 3;
        // the label never wraps and asks for less than the row it gets: growing it would resize a
        // shell the user cannot resize back, so a long message clips and lives on in the tooltip
        statusData.widthHint = 200;
        statusLabel.setLayoutData(statusData);

        settingsButton = Dialogs.pushButton(footer, "Settings...", this::openSettings);
        settingsButton.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));

        // the middle column is what pushes Connect to the right edge
        new Label(footer, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        connectButton = Dialogs.pushButton(footer, CONNECT, this::connectPressed);
        GridData connectData = new GridData(SWT.END, SWT.CENTER, false, false);
        connectData.widthHint = 100;
        connectButton.setLayoutData(connectData);
    }

    private void center() {
        Monitor monitor = display.getPrimaryMonitor();
        if (monitor == null) {
            return;
        }

        Rectangle area = monitor.getClientArea();
        Point size = shell.getSize();
        shell.setLocation(area.x + (area.width - size.x) / 2, area.y + (area.height - size.y) / 2);
    }

    private void openSettings() {
        SettingsDialog.open(shell, packages, registry, settings, manifests);
        fillServers();
        fillLoginFromSelection();
        focusFirstFieldNeedingInput();
    }

    private void fillServers() {
        List<ServerEntry> known = new ArrayList<>(registry.entries());
        known.sort(DisplayFormat.byLastUsed());

        String typed = serverCombo.getText();
        serverCombo.setItems(known.stream().map(DisplayFormat::address).toArray(String[]::new));
        if (!typed.isEmpty()) {
            serverCombo.setText(typed);
        } else if (!known.isEmpty()) {
            serverCombo.select(0);
        }
    }

    private void fillLoginFromSelection() {
        if (!DisplayFormat.canPrefillLogin(loginField.getText(), autoFilledLogin)) {
            return;
        }

        String login = selectedServer().map(entry -> DisplayFormat.lastLogin(entry.lastLogin())).orElse("");
        loginField.setText(login);
        autoFilledLogin = login;
    }

    private void focusFirstFieldNeedingInput() {
        switch (DisplayFormat.fieldToFocus(serverCombo.getText(), loginField.getText())) {
            case SERVER -> serverCombo.setFocus();
            case LOGIN -> loginField.setFocus();
            case PASSWORD -> passwordField.setFocus();
        }
    }

    private Optional<ServerEntry> selectedServer() {
        return DisplayFormat.parseAddress(serverCombo.getText()).address().flatMap(address -> registry.find(address.host(), address.port()));
    }

    private void connectPressed() {
        AtomicBoolean running = attempt;
        if (running == null) {
            startConnect();
            return;
        }

        running.set(true);
        connectButton.setEnabled(false);
        setStatus("Cancelling...", false);
    }

    private void startConnect() {
        DisplayFormat.AddressParse parsed = DisplayFormat.parseAddress(serverCombo.getText());
        if (!parsed.valid()) {
            setStatus(parsed.error().orElseThrow(), true);
            serverCombo.setFocus();
            return;
        }

        DisplayFormat.ServerAddress address = parsed.address().orElseThrow();
        String login = loginField.getText().trim();
        if (login.isEmpty()) {
            setStatus("Enter a user name.", true);
            loginField.setFocus();
            return;
        }

        ServerEntry server = registry.find(address.host(), address.port()).orElseGet(() -> ServerEntry.of(address.host(), address.port()));

        String password = passwordField.getText();
        AtomicBoolean cancel = new AtomicBoolean();
        attempt = cancel;
        eta = new DownloadEta();
        setBusy(true);
        setStatus("", false);

        ConnectAction action = connectAction;
        Thread worker = new Thread(() -> {
            ConnectFlow.Outcome outcome = ConnectFlow.Outcome.STOPPED;
            try {
                outcome = action.connect(server, login, password, cancel::get);
            } catch (Throwable t) {
                showDiagnostic("Unexpected launcher failure: " + t);
            } finally {
                finished(outcome);
            }
        }, "connect-flow");
        worker.setDaemon(true);
        worker.start();
    }

    private void finished(ConnectFlow.Outcome outcome) {
        async(() -> {
            attempt = null;
            setBusy(false);
            fillServers();
            if (outcome == ConnectFlow.Outcome.RETRY_LOGIN) {
                passwordField.selectAll();
                passwordField.setFocus();
            } else if (outcome == ConnectFlow.Outcome.STOPPED) {
                focusFirstFieldNeedingInput();
            }
        });
    }

    private void setBusy(boolean busy) {
        serverCombo.setEnabled(!busy);
        loginField.setEnabled(!busy);
        passwordField.setEnabled(!busy);
        settingsButton.setEnabled(!busy);

        connectButton.setEnabled(true);
        connectButton.setText(busy ? CANCEL : CONNECT);
        connectButton.getParent().layout();
    }

    private void setStatus(String message, boolean error) {
        setStatus(message, message, error);
    }

    private void setStatus(String message, String detail, boolean error) {
        String text = (message != null) ? message : "";
        String tooltip = (detail != null) ? detail : text;
        statusLabel.setText(text);
        statusLabel.setToolTipText(tooltip.isEmpty() ? null : tooltip);
        statusLabel.setForeground(error ? display.getSystemColor(SWT.COLOR_RED) : null);
        statusLabel.getParent().layout();
    }

    @Override
    public void showProgress(String message) {
        async(() -> setStatus(message, false));
    }

    @Override
    public void showDownloadProgress(String version, long downloaded, long total) {
        async(() -> {
            eta.sample(downloaded, total);
            setStatus(DisplayFormat.downloadStatus(version, downloaded, total, eta.remaining()), false);
        });
    }

    @Override
    public boolean confirmDownload(String branch, ServerVersion version, String manifestHost) {
        return syncConfirm(() -> messageBox(SWT.ICON_QUESTION | SWT.YES | SWT.NO, "Download nxmc", DisplayFormat.downloadPrompt(branch, version, manifestHost)) == SWT.YES);
    }

    @Override
    public boolean confirmUpdate(String branch, ServerVersion cached, ReleaseManifest.Release release) {
        return syncConfirm(() -> messageBox(SWT.ICON_QUESTION | SWT.YES | SWT.NO, "Update available", DisplayFormat.updatePrompt(branch, cached, release)) == SWT.YES);
    }

    @Override
    public String promptNewPassword(int graceLogins, String rejection) {
        return sync(() -> PasswordExpiredDialog.open(shell, graceLogins, rejection));
    }

    @Override
    public boolean confirmRedownload(LaunchFailure failure) {
        return syncConfirm(() -> messageBox(SWT.ICON_QUESTION | SWT.YES | SWT.NO, "nxmc did not start", DisplayFormat.redownloadPrompt(failure)) == SWT.YES);
    }

    @Override
    public void showFailure(String summary, String detail) {
        async(() -> setStatus(summary, detail, true));
    }

    @Override
    public void showWarning(String message) {
        sync(() -> messageBox(SWT.ICON_WARNING | SWT.OK, "Launcher", message));
    }

    @Override
    public void showDiagnostic(String message) {
        sync(() -> {
            setStatus("", false);
            return messageBox(SWT.ICON_ERROR | SWT.OK, "Cannot start nxmc", message);
        });
    }

    @Override
    public void launchSucceeded() {
        async(() -> {
            setStatus("nxmc started.", false);
            shell.close();
        });
    }

    @Override
    public int selectMethod(List<String> methods) {
        Integer selection = sync(() -> TwoFactorDialog.selectMethod(shell, methods));
        return (selection != null) ? selection : TwoFactorDialog.CANCELLED;
    }

    @Override
    public String enterCode(String challenge, String qrLabel) {
        return sync(() -> TwoFactorDialog.enterCode(shell, challenge, qrLabel));
    }

    private int messageBox(int style, String title, String message) {
        MessageBox box = new MessageBox(shell, style);
        box.setText(title);
        box.setMessage((message != null) ? message : "");
        return box.open();
    }

    private void async(Runnable action) {
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (!shell.isDisposed()) {
                action.run();
            }
        });
    }

    private <T> T sync(Supplier<T> action) {
        if (display.isDisposed()) {
            return null;
        }

        AtomicReference<T> result = new AtomicReference<>();
        display.syncExec(() -> {
            if (!shell.isDisposed()) {
                result.set(action.get());
            }
        });
        return result.get();
    }

    private boolean syncConfirm(Supplier<Boolean> action) {
        return Boolean.TRUE.equals(sync(action));
    }

    @FunctionalInterface
    public interface ConnectAction {
        ConnectFlow.Outcome connect(ServerEntry server, String login, String password, CancelToken cancel);
    }
}
