package org.netxms.launcher;

import java.nio.file.Path;

final class JavaSource {
    private JavaSource() {
    }

    static String pathLiteral(Path path) {
        return escape(path.toAbsolutePath().toString());
    }

    static String escape(String path) {
        return path.replace("\\", "\\\\");
    }
}
