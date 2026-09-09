package org.netxms.launcher;

import java.util.List;

public interface TwoFactorPrompt {
    int selectMethod(List<String> methods);

    String enterCode(String challenge, String qrLabel);
}
