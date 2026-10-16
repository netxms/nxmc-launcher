package org.netxms.launcher.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.List;

public final class TwoFactorDialog {
    public static final int CANCELLED = -1;

    private TwoFactorDialog() {
    }

    public static int selectMethod(Shell parent, List<String> methods) {
        if ((methods == null) || methods.isEmpty()) {
            return CANCELLED;
        }
        if (methods.size() == 1) {
            return 0;
        }

        MethodDialog dialog = new MethodDialog(parent, methods);
        return dialog.show();
    }

    public static String enterCode(Shell parent, String challenge, String qrLabel) {
        CodeDialog dialog = new CodeDialog(parent, challenge, qrLabel);
        return dialog.show();
    }

    private static final class MethodDialog {
        private final Shell shell;
        private final org.eclipse.swt.widgets.List methodList;
        private int result = CANCELLED;

        MethodDialog(Shell parent, List<String> methods) {
            shell = Dialogs.modalShell(parent, "Two-factor authentication", 1);

            new Label(shell, SWT.NONE).setText("Select authentication method");

            methodList = new org.eclipse.swt.widgets.List(shell, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
            GridData listData = new GridData(SWT.FILL, SWT.FILL, true, true);
            listData.widthHint = 260;
            listData.heightHint = 110;
            methodList.setLayoutData(listData);
            for (String method : methods)
                methodList.add(method);
            methodList.select(0);

            Dialogs.okCancel(shell, 1, this::accept);
            shell.pack();
        }

        private void accept() {
            int selected = methodList.getSelectionIndex();
            // a cleared selection reads as CANCELLED, which aborts the login without a word — the
            // list starts with one selected, but ctrl-click deselects it even in a SINGLE list
            if (selected < 0) {
                Dialogs.complain(shell, "Select an authentication method.");
                methodList.setFocus();
                return;
            }
            result = selected;
            shell.close();
        }

        int show() {
            Dialogs.runEventLoop(shell);
            return result;
        }
    }

    private static final class CodeDialog {
        private final Shell shell;
        private final Text codeField;
        private String result;

        CodeDialog(Shell parent, String challenge, String qrLabel) {
            shell = Dialogs.modalShell(parent, "Two-factor authentication", 1);

            Label prompt = new Label(shell, SWT.WRAP);
            prompt.setText(((challenge != null) && !challenge.isBlank()) ? challenge : "Enter the verification code");
            GridData promptData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            promptData.widthHint = 320;
            prompt.setLayoutData(promptData);

            if ((qrLabel != null) && !qrLabel.isBlank()) {
                createSecret(qrLabel);
            }

            codeField = new Text(shell, SWT.BORDER);
            codeField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Dialogs.okCancel(shell, 1, this::accept);
            shell.pack();
        }

        private void createSecret(String qrLabel) {
            new Label(shell, SWT.NONE).setText("Register this secret in your authenticator application:");
            Text secret = new Text(shell, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP);
            secret.setText(qrLabel);
            GridData secretData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            secretData.widthHint = 320;
            secret.setLayoutData(secretData);
        }

        private void accept() {
            String code = codeField.getText().trim();
            if (code.isEmpty()) {
                Dialogs.complain(shell, "Enter the verification code.");
                codeField.setFocus();
                return;
            }
            result = code;
            shell.close();
        }

        String show() {
            Dialogs.runEventLoop(shell);
            return result;
        }
    }
}
