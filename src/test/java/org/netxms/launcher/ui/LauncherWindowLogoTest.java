package org.netxms.launcher.ui;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The four logo resources and the two rules {@code LauncherWindow.loadLogo} applies to them.
 * {@code ImageData} needs no {@code Display} and loads no native, so nothing here starts one; the
 * system colour read and the rendered result need a manual run in both appearances.
 */
class LauncherWindowLogoTest {
    private static Size sizeOf(String name) throws IOException {
        ImageData image = read(name);
        return new Size(image.width, image.height);
    }

    /**
     * Mean luminance of the painted pixels: the wordmark is the only part that differs between the variants.
     */
    private static double brightness(String name) throws IOException {
        ImageData image = read(name);
        double sum = 0;
        int painted = 0;
        for (int y = 0; y < image.height; y++)
            for (int x = 0; x < image.width; x++) {
                if (image.getAlpha(x, y) < 128) {
                    continue;
                }

                RGB colour = image.palette.getRGB(image.getPixel(x, y));
                sum += 0.299 * colour.red + 0.587 * colour.green + 0.114 * colour.blue;
                painted++;
            }

        assertTrue(painted > 0, name + " has no pixel opaque enough to measure");
        return sum / painted;
    }

    private static ImageData read(String name) throws IOException {
        try (InputStream stream = LauncherWindow.class.getResourceAsStream(name)) {
            assertNotNull(stream, name + " is not on the classpath beside LauncherWindow");
            return new ImageData(stream);
        }
    }

    @Test
    void bothVariantsShipAtTheSizeTheFormIsLaidOutBeside() throws IOException {
        assertEquals(new Size(150, 166), sizeOf("logo.png"));
        assertEquals(new Size(150, 166), sizeOf("logo-dark.png"));
    }

    @Test
    void everyRetinaVariantIsExactlyDoubleItsBase() throws IOException {
        assertEquals(sizeOf("logo.png").doubled(), sizeOf("logo@2x.png"));
        assertEquals(sizeOf("logo-dark.png").doubled(), sizeOf("logo-dark@2x.png"));
    }

    @Test
    void theDarkVariantIsTheLighterArtwork() throws IOException {
        assertTrue(brightness("logo-dark.png") > brightness("logo.png"),
                "logo-dark.png is no lighter than logo.png " + "-" + " the two look copied from one file, or swapped");
        assertTrue(brightness("logo-dark@2x.png") > brightness("logo@2x.png"),
                "logo-dark@2x.png is no lighter than " + "logo@2x.png - the two look copied from one file, or swapped");
    }

    @Test
    void aDarkBackgroundTakesTheDarkVariantAndALightOneTheLight() {
        assertEquals("logo-dark", LauncherWindow.logoBase(new RGB(0x28, 0x28, 0x28)));
        assertEquals("logo", LauncherWindow.logoBase(new RGB(0xF0, 0xF0, 0xF0)));
    }

    @Test
    void bothStemsNameResourcesThatAreThere() throws IOException {
        for (RGB background : new RGB[]{
                new RGB(0xF0, 0xF0, 0xF0),
                new RGB(0x28, 0x28, 0x28)
        }) {
            String base = LauncherWindow.logoBase(background);
            assertEquals(sizeOf(base + ".png").doubled(), sizeOf(base + "@2x.png"), base);
        }
    }

    @Test
    void theProviderAnswersOnlyTheZoomsItHoldsDataSizedFor() throws IOException {
        ImageData normal = read("logo.png");
        ImageData retina = read("logo@2x.png");
        ImageDataProvider provider = LauncherWindow.logoProvider(normal, retina);

        assertSame(normal, provider.getImageData(100));
        assertSame(retina, provider.getImageData(200));
        for (int zoom : new int[]{
                50,
                75,
                125,
                150,
                175,
                250,
                300,
                400
        })
            assertNull(provider.getImageData(zoom),
                    "zoom " + zoom + " got data sized for another zoom - SWT would " + "draw the logo at the wrong " + "size");
    }

    @Test
    void aMissingRetinaVariantReadsAsNothingAtZoom200() throws IOException {
        ImageDataProvider provider = LauncherWindow.logoProvider(read("logo.png"), null);

        assertNotNull(provider.getImageData(100), "zoom 100 must never be null - SWT raises ERROR_INVALID_ARGUMENT");
        assertNull(provider.getImageData(200), "a missing @2x has to read as nothing, so SWT falls back to the 1x");
    }

    private record Size(int width, int height) {
        Size doubled() {
            return new Size(width * 2, height * 2);
        }
    }
}
