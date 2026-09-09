package org.netxms.launcher;

import org.eclipse.swt.widgets.Display;
import org.netxms.launcher.ui.LauncherWindow;

import java.util.Arrays;

public final class Launcher {
    public static final String VERSION = "1.0.0-SNAPSHOT";

    private Launcher() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("-version") || Arrays.asList(args).contains("--version")) {
            System.out.println("nxmc-launcher " + VERSION);
            return;
        }

        AppDirs dirs = AppDirs.standard();
        ServerRegistry registry = ServerRegistry.load(dirs.configDir());
        PackageManager packages = new PackageManager(dirs.dataDir());
        packages.cleanupStaleTemporaryFiles();

        LauncherSettings settings = LauncherSettings.load(dirs.configDir());
        ManifestClient manifests = new ManifestClient();
        Display display = new Display();
        try {
            LauncherWindow window = new LauncherWindow(display, registry, packages, settings, manifests);
            NxcpSessionService sessionService = new NxcpSessionService();
            ConnectFlow flow = new ConnectFlow(sessionService, manifests, packages, new AppRunner(), registry, window, settings);
            window.setConnectAction(flow::connect);
            window.open();
        } finally {
            display.dispose();
        }
    }
}
