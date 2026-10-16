package org.netxms.launcher.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

public final class PasswordExpiredDialog {
    private final Shell shell;
    private final Text passwordField;
    private final Text confirmationField;
    private String result;

    private PasswordExpiredDialog(Shell parent, int graceLogins, String rejection) {
        shell = Dialogs.modalShell(parent, "Password expired", 2);

        Label message = new Label(shell, SWT.WRAP);
        message.setText(DisplayFormat.passwordExpiredMessage(graceLogins, rejection));
        GridData messageData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        messageData.horizontalSpan = 2;
        messageData.widthHint = 320;
        message.setLayoutData(messageData);

        new Label(shell, SWT.NONE).setText("New password");
        passwordField = new Text(shell, SWT.BORDER | SWT.PASSWORD);
        passwordField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(shell, SWT.NONE).setText("Confirm password");
        confirmationField = new Text(shell, SWT.BORDER | SWT.PASSWORD);
        confirmationField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Dialogs.okCancel(shell, 2, this::accept);
        shell.pack();
    }

    public static String open(Shell parent, int graceLogins, String rejection) {
        return new PasswordExpiredDialog(parent, graceLogins, rejection).show();
    }

    private void accept() {
        String password = passwordField.getText();
        if (password.isEmpty()) {
            Dialogs.complain(shell, "Enter the new password.");
            passwordField.setFocus();
            return;
        }
        if (!password.equals(confirmationField.getText())) {
            Dialogs.complain(shell, "The passwords do not match.");
            confirmationField.selectAll();
            confirmationField.setFocus();
            return;
        }

        result = password;
        shell.close();
    }

    private String show() {
        Dialogs.runEventLoop(shell);
        return result;
    }
}
