package org.netxms.launcher.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

final class Dialogs {
    private Dialogs() {
    }

    static Shell modalShell(Shell parent, String title, int columns) {
        return modalShell(parent, title, columns, SWT.NONE);
    }

    static Shell modalShell(Shell parent, String title, int columns, int extraStyle) {
        Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | extraStyle);
        shell.setText(title);
        GridLayout layout = new GridLayout(columns, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        shell.setLayout(layout);
        return shell;
    }

    static void okCancel(Shell shell, int horizontalSpan, Runnable onOk) {
        Composite buttons = new Composite(shell, SWT.NONE);
        GridLayout layout = new GridLayout(2, true);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        buttons.setLayout(layout);
        GridData buttonsData = new GridData(SWT.END, SWT.CENTER, true, false);
        buttonsData.horizontalSpan = horizontalSpan;
        buttons.setLayoutData(buttonsData);

        Button ok = pushButton(buttons, "OK", onOk);
        ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button cancel = pushButton(buttons, "Cancel", shell::close);
        cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        shell.setDefaultButton(ok);
    }

    static Button pushButton(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        button.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                action.run();
            }
        });
        return button;
    }

    static Button checkBox(Composite parent, String text, boolean selected, Runnable action) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(text);
        button.setSelection(selected);
        button.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                action.run();
            }
        });
        return button;
    }

    static TableColumn column(Table table, String title, int width) {
        TableColumn column = new TableColumn(table, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
        return column;
    }

    static void complain(Shell shell, String message) {
        messageBox(shell, SWT.ICON_WARNING | SWT.OK, message);
    }

    static void error(Shell shell, String message) {
        messageBox(shell, SWT.ICON_ERROR | SWT.OK, message);
    }

    static void inform(Shell shell, String message) {
        messageBox(shell, SWT.ICON_INFORMATION | SWT.OK, message);
    }

    static boolean confirm(Shell shell, String message) {
        return messageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO, message) == SWT.YES;
    }

    static void runEventLoop(Shell shell) {
        shell.open();
        while (!shell.isDisposed()) {
            if (!shell.getDisplay().readAndDispatch()) {
                shell.getDisplay().sleep();
            }
        }
    }

    private static int messageBox(Shell shell, int style, String message) {
        MessageBox box = new MessageBox(shell, style);
        box.setText(shell.getText());
        box.setMessage((message != null) ? message : "");
        return box.open();
    }
}
