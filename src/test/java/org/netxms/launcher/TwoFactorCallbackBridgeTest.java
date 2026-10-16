package org.netxms.launcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TwoFactorCallbackBridgeTest {
    @Test
    void forwardsMethodSelection() {
        RecordingPrompt prompt = new RecordingPrompt();
        prompt.methodToSelect = 1;
        NxcpSessionService.TwoFactorCallbackBridge bridge = new NxcpSessionService.TwoFactorCallbackBridge(prompt);

        assertEquals(1, bridge.selectMethod(List.of("TOTP", "Message")));
        assertEquals(List.of("TOTP", "Message"), prompt.offeredMethods);
    }

    @Test
    void forwardsCancelledMethodSelection() {
        RecordingPrompt prompt = new RecordingPrompt();
        prompt.methodToSelect = -1;
        assertEquals(-1, new NxcpSessionService.TwoFactorCallbackBridge(prompt).selectMethod(List.of("TOTP")));
    }

    @Test
    void toleratesMissingMethodList() {
        RecordingPrompt prompt = new RecordingPrompt();
        new NxcpSessionService.TwoFactorCallbackBridge(prompt).selectMethod(null);
        assertTrue(prompt.offeredMethods.isEmpty());
    }

    @Test
    void forwardsChallengeAndResponse() {
        RecordingPrompt prompt = new RecordingPrompt();
        prompt.response = "654321";
        NxcpSessionService.TwoFactorCallbackBridge bridge = new NxcpSessionService.TwoFactorCallbackBridge(prompt);

        assertEquals("654321", bridge.getUserResponse("enter code", "otpauth:///label", true));
        assertEquals("enter code", prompt.challenge);
        assertEquals("otpauth:///label", prompt.qrLabel);
    }

    @Test
    void forwardsCancelledCodeEntry() {
        RecordingPrompt prompt = new RecordingPrompt();
        prompt.response = null;
        assertNull(new NxcpSessionService.TwoFactorCallbackBridge(prompt).getUserResponse(null, null, false));
    }

    @Test
    void neverOffersOrStoresTrustedDeviceToken() {
        NxcpSessionService.TwoFactorCallbackBridge bridge =
                new NxcpSessionService.TwoFactorCallbackBridge(new RecordingPrompt());
        assertNull(bridge.getTrustedDeviceToken(42, "admin"));
        assertDoesNotThrow(() -> bridge.saveTrustedDeviceToken(42, "admin", new byte[]{
                1,
                2,
                3
        }));
    }

    private static final class RecordingPrompt implements TwoFactorPrompt {
        List<String> offeredMethods;
        String challenge;
        String qrLabel;
        int methodToSelect = 0;
        String response = "123456";

        @Override
        public int selectMethod(List<String> methods) {
            offeredMethods = methods;
            return methodToSelect;
        }

        @Override
        public String enterCode(String challenge, String qrLabel) {
            this.challenge = challenge;
            this.qrLabel = qrLabel;
            return response;
        }
    }
}
