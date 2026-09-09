package org.netxms.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaSourceTest {
    @Test
    void doublesEveryBackslashOfAWindowsPath() {
        assertEquals("C:\\\\Users\\\\tester\\\\AppData\\\\Local\\\\Temp\\\\junit\\\\args.txt",
                JavaSource.escape("C" + ":\\Users\\tester\\AppData\\Local\\Temp\\junit\\args.txt"));
    }

    @Test
    void leavesAPosixPathAlone() {
        assertEquals("/tmp/junit-8123/args.txt", JavaSource.escape("/tmp/junit-8123/args.txt"));
    }

    @Test
    void embedsTheAbsoluteFormOfAPathEscaped() {
        Path relative = Path.of("args.txt");
        String absolute = relative.toAbsolutePath().toString();

        assertEquals(absolute.replace("\\", "\\\\"), JavaSource.pathLiteral(relative));
    }
}
