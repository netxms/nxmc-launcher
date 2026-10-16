package org.netxms.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LauncherVersionTest {
    @Test
    void carriesTheVersionTheBuildWasMadeFrom() {
        assertEquals(System.getProperty("launcher.expectedVersion"), LauncherVersion.VERSION);
    }

    @Test
    void namesItselfAndItsVersionToDownloadServers() {
        assertEquals("nxmc-launcher/" + LauncherVersion.VERSION, ManifestClient.USER_AGENT);
    }
}
