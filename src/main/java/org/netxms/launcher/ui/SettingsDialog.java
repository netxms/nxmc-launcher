package org.netxms.launcher.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.netxms.launcher.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class SettingsDialog {
    private final Shell shell;
    private final PackageManager packages;
    private final ServerRegistry registry;
    private final LauncherSettings settings;
    private final ConnectFlow.ManifestSource manifests;
    private final Table serverTable;
    private final Button forgetButton;
    private final Table table;
    private final Label summaryLabel;
    private final Button deleteButton;
    private final Button checkButton;
    private final Button clearButton;
    private final Button checkForUpdatesButton;
    private int cacheEntries;

    private SettingsDialog(Shell parent, PackageManager packages, ServerRegistry registry, LauncherSettings settings, ConnectFlow.ManifestSource manifests) {
        this.packages = packages;
        this.registry = registry;
        this.settings = settings;
        this.manifests = manifests;

        shell = Dialogs.modalShell(parent, "Settings", 2, SWT.RESIZE);

        Group servers = new Group(shell, SWT.NONE);
        servers.setText("Servers");
        servers.setLayout(new GridLayout(2, false));
        GridData serversData = new GridData(SWT.FILL, SWT.TOP, true, false);
        serversData.horizontalSpan = 2;
        servers.setLayoutData(serversData);

        serverTable = new Table(servers, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        serverTable.setHeaderVisible(true);
        serverTable.setLinesVisible(true);
        GridData serverTableData = new GridData(SWT.FILL, SWT.FILL, true, false);
        serverTableData.heightHint = 100;
        serverTable.setLayoutData(serverTableData);
        Dialogs.column(serverTable, "Server", 250);
        Dialogs.column(serverTable, "Last used", 140);
        serverTable.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateButtons();
            }
        });

        Composite serverButtons = new Composite(servers, SWT.NONE);
        GridLayout serverButtonLayout = new GridLayout();
        serverButtonLayout.marginWidth = 0;
        serverButtonLayout.marginHeight = 0;
        serverButtons.setLayout(serverButtonLayout);
        serverButtons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        forgetButton = Dialogs.pushButton(serverButtons, "Forget", this::forgetSelected);

        Label caption = new Label(shell, SWT.WRAP);
        caption.setText("Cached nxmc builds in " + packages.root());
        GridData captionData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        captionData.horizontalSpan = 2;
        captionData.widthHint = 460;
        caption.setLayoutData(captionData);

        table = new Table(shell, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 160;
        table.setLayoutData(tableData);
        Dialogs.column(table, "Branch", 80);
        Dialogs.column(table, "Version", 110);
        Dialogs.column(table, "Size", 90);
        Dialogs.column(table, "Last used", 140);
        table.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateButtons();
            }
        });

        Composite buttons = new Composite(shell, SWT.NONE);
        GridLayout buttonLayout = new GridLayout();
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttons.setLayout(buttonLayout);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        deleteButton = Dialogs.pushButton(buttons, "Delete selected", this::deleteSelected);
        checkButton = Dialogs.pushButton(buttons, "Check for updates now", this::checkForUpdatesNow);
        clearButton = Dialogs.pushButton(buttons, "Clear cache", this::clearCache);
        // no selection to act on, so it is never disabled and updateButtons has nothing to say about it
        Dialogs.pushButton(buttons, "Add from file...", this::addFromFile);

        summaryLabel = new Label(shell, SWT.NONE);
        GridData summaryData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        summaryData.horizontalSpan = 2;
        summaryLabel.setLayoutData(summaryData);

        checkForUpdatesButton = Dialogs.checkBox(shell, "Check for newer nxmc builds when connecting", settings.checkForUpdates().orElse(Boolean.FALSE), this::saveCheckForUpdates);
        GridData checkForUpdatesData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        checkForUpdatesData.horizontalSpan = 2;
        checkForUpdatesButton.setLayoutData(checkForUpdatesData);

        Button close = Dialogs.pushButton(shell, "Close", shell::close);
        GridData closeData = new GridData(SWT.END, SWT.CENTER, true, false);
        closeData.horizontalSpan = 2;
        closeData.widthHint = 120;
        close.setLayoutData(closeData);
        shell.setDefaultButton(close);

        refreshServers();
        refresh();
        shell.pack();
    }

    public static void open(Shell parent, PackageManager packages, ServerRegistry registry, LauncherSettings settings, ConnectFlow.ManifestSource manifests) {
        new SettingsDialog(parent, packages, registry, settings, manifests).show();
    }

    private void refreshServers() {
        List<ServerEntry> known = new ArrayList<>(registry.entries());
        known.sort(DisplayFormat.byLastUsed());
        serverTable.removeAll();
        for (ServerEntry entry : known) {
            TableItem item = new TableItem(serverTable, SWT.NONE);
            item.setText(0, DisplayFormat.address(entry));
            item.setText(1, DisplayFormat.serverLastUsed(entry));
            item.setData(entry);
        }
        updateButtons();
    }

    private void refresh() {
        List<PackageManager.CachedPackage> cached = packages.cachedPackages();
        table.removeAll();
        long total = 0;
        for (PackageManager.CachedPackage entry : cached) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, entry.branch());
            item.setText(1, DisplayFormat.version(entry.version()));
            item.setText(2, DisplayFormat.size(entry.size()));
            item.setText(3, DisplayFormat.lastUsed(entry.lastUsed()));
            item.setData(entry);
            total += entry.size();
        }
        cacheEntries = packages.cacheEntries().size();
        summaryLabel.setText(DisplayFormat.cacheSummary(cached.size(), total, cacheEntries));
        updateButtons();
    }

    private void updateButtons() {
        forgetButton.setEnabled(selectedServer() != null);
        deleteButton.setEnabled(selected() != null);
        checkButton.setEnabled(selected() != null);
        clearButton.setEnabled(cacheEntries > 0);
    }

    private PackageManager.CachedPackage selected() {
        TableItem[] selection = table.getSelection();
        return (selection.length > 0) ? (PackageManager.CachedPackage) selection[0].getData() : null;
    }

    private ServerEntry selectedServer() {
        TableItem[] selection = serverTable.getSelection();
        return (selection.length > 0) ? (ServerEntry) selection[0].getData() : null;
    }

    private void forgetSelected() {
        ServerEntry entry = selectedServer();
        if (entry == null) {
            return;
        }

        registry.remove(entry.address(), entry.port());
        try {
            registry.save();
        } catch (IOException e) {
            Dialogs.error(shell, "Cannot update the server list " + registry.file() + ": " + e.getMessage());
        }
        refreshServers();
    }

    private void deleteSelected() {
        PackageManager.CachedPackage entry = selected();
        if ((entry == null) || !Dialogs.confirm(shell, DisplayFormat.deleteBranchPrompt(entry))) {
            return;
        }

        try {
            packages.delete(entry.branch());
        } catch (PackageException e) {
            Dialogs.error(shell, e.getMessage());
        }
        refresh();
    }

    private void saveCheckForUpdates() {
        settings.setCheckForUpdates(checkForUpdatesButton.getSelection());
        try {
            settings.save();
        } catch (IOException e) {
            Dialogs.error(shell, "Cannot save the launcher settings to " + settings.file() + ": " + e.getMessage());
        }
    }

    private void checkForUpdatesNow() {
        PackageManager.CachedPackage entry = selected();
        if (entry == null) {
            return;
        }

        AtomicReference<DisplayFormat.UpdateCheckResult> result = new AtomicReference<>();
        BusyIndicator.showWhile(shell.getDisplay(), () -> result.set(checkFor(entry)));

        if (result.get().failure()) {
            Dialogs.error(shell, result.get().message());
        } else {
            Dialogs.inform(shell, result.get().message());
        }
    }

    private DisplayFormat.UpdateCheckResult checkFor(PackageManager.CachedPackage entry) {
        try {
            ReleaseManifest manifest = manifests.fetch();

            Optional<PackageManager.CachedPackage> compared = packages.cached(entry.branch());
            if (compared.isEmpty()) {
                return DisplayFormat.updateBranchGone(entry);
            }

            Optional<ReleaseManifest.Release> newer = packages.updateAvailable(compared.get(), manifest);
            DisplayFormat.UpdateCheckResult answer = DisplayFormat.updateCheckResult(compared.get(), manifest, newer);

            return recordCheck(compared.get(), entry.checkStamp(), newer, answer.stamp()).orElse(answer);
        } catch (ManifestException e) {
            return DisplayFormat.updateCheckResult(e);
        }
    }

    private Optional<DisplayFormat.UpdateCheckResult> recordCheck(PackageManager.CachedPackage compared, CheckStamp servedArm, Optional<ReleaseManifest.Release> newer, DisplayFormat.UpdateCheckStamp stamp) {
        String branch = compared.branch();
        try {
            switch (stamp) {
                case ARMED:
                    // ARMED is what a published newer build is stamped with, so there is one to name here
                    if (!packages.armUpdateCheck(branch)) {
                        return Optional.of(DisplayFormat.updateBranchGone(compared, newer.orElseThrow()));
                    }
                    break;
                case CHECKED:
                    // the stamp is also the read that says whether the build is still there, which the
                    // read above cannot answer for the moment after itself: "up to date" about a build
                    // another instance removed in between is a comparison that no longer stands
                    if (!packages.markChecked(branch, servedArm)) {
                        return Optional.of(DisplayFormat.updateBranchGone(compared));
                    }
                    break;
                case NONE:
                    break;
            }
            return Optional.empty();
        } catch (PackageException e) {
            return newer.map(release -> DisplayFormat.updateNotArmed(compared, release, e.getMessage()));
        }
    }

    private void addFromFile() {
        FileDialog dialog = new FileDialog(shell, SWT.OPEN);
        dialog.setText("Add nxmc build from file");
        dialog.setFilterExtensions("*.jar", "*.*");
        dialog.setFilterNames("nxmc standalone jar (*.jar)", "All files");
        String chosen = dialog.open();
        if (chosen == null) {
            return;
        }

        try {
            PackageManager.IdentifiedBuild identified = busy(() -> packages.identify(Path.of(chosen)));
            Optional<PackageManager.CachedPackage> existing = packages.cached(identified.branch());
            if (existing.isPresent() && !Dialogs.confirm(shell, DisplayFormat.importReplacePrompt(existing.get(), identified))) {
                return;
            }

            busy(() -> packages.importBuild(identified));
        } catch (PackageException e) {
            Dialogs.error(shell, e.getMessage());
        }
        refresh();
    }

    private <T> T busy(CacheOperation<T> operation) throws PackageException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<PackageException> failure = new AtomicReference<>();
        BusyIndicator.showWhile(shell.getDisplay(), () -> {
            try {
                result.set(operation.run());
            } catch (PackageException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private void clearCache() {
        if (!Dialogs.confirm(shell, DisplayFormat.clearCachePrompt(table.getItemCount(), cacheEntries))) {
            return;
        }

        try {
            packages.clearCache();
        } catch (PackageException e) {
            Dialogs.error(shell, e.getMessage());
        }
        refresh();
    }

    private void show() {
        Dialogs.runEventLoop(shell);
    }

    @FunctionalInterface
    private interface CacheOperation<T> {
        T run() throws PackageException;
    }
}
