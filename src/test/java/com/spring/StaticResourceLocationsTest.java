package com.spring;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where static assets are served from — and, above all, that PRODUCTION IS UNCHANGED.
 *
 * <h3>The defect these guard</h3>
 * The app serves static files from {@code target/classes}, so editing
 * {@code src/main/resources/static/js/…} did nothing until the next {@code mvn package}. The failure is
 * silent and complete: the browser gets the OLD bytes under a content hash computed over those old bytes, so
 * the URL looks correctly versioned and everything appears to work. A fix can be written, tested, seen "not
 * to work", and rewritten several times before anyone compares the served file to the file on disk — which
 * is exactly what happened, across three test runs.
 *
 * <h3>Why the fix must be self-disabling rather than profile-guarded</h3>
 * The convenience is a path on disk resolved against the working directory, used only when that directory
 * exists. A deployed jar runs from {@code /} in a container built by {@code mvn clean package} from a fresh
 * checkout, so the directory is absent and the location is dropped. There is no property for anyone to set
 * wrongly on a server — but "there is no way to get this wrong" is a claim, so it is asserted here.
 */
class StaticResourceLocationsTest {

    private static final String[] PACKAGED = {
        "classpath:/META-INF/resources/",
        "classpath:/resources/",
        "classpath:/static/",
        "classpath:/public/"
    };

    @Test
    @DisplayName("⭐ with no working copy, the locations are EXACTLY what they always were")
    void productionIsUntouched() {
        assertSame(PACKAGED, MvcConfig.withLiveSourceFirst(PACKAGED, null),
                "a deployment must not even get a copied array, let alone a different one");

        String[] js = MvcConfig.locationsUnder("js", null);
        assertArrayEquals(new String[] {
            "classpath:/META-INF/resources/js/",
            "classpath:/resources/js/",
            "classpath:/static/js/",
            "classpath:/public/js/"
        }, js);
    }

    @Test
    @DisplayName("⭐ with a working copy, it is tried FIRST — the packaged copy still follows")
    void workingCopyWinsButPackagedRemains() {
        String live = "file:/repo/src/main/resources/static/";
        String[] js = MvcConfig.locationsUnder("js", live);

        assertEquals(PACKAGED.length + 1, js.length, "one location added, none removed");
        assertEquals("file:/repo/src/main/resources/static/js/", js[0],
                "the working copy is consulted before the packaged asset, or the edit is invisible");
        // Order matters and the fall-through matters: a file that exists ONLY in the jar (a webjar, a
        // generated asset) must still resolve, so the packaged locations are kept, in their original order.
        for (int i = 0; i < PACKAGED.length; i++) {
            assertEquals(PACKAGED[i] + "js/", js[i + 1]);
        }
    }

    @Test
    @DisplayName("the directory suffix is applied to the working copy too")
    void theDirectoryTrapAppliesToBothTiers() {
        /*
         * ResourceHttpRequestHandler resolves the path WITHIN THE MAPPING. Mapped at "/js/**", "/js/a.js"
         * becomes "a.js" — so every location must already end in "js/" or every script 404s. That trap is
         * documented on addResourceHandlers; this asserts the new tier did not step into it.
         */
        for (String dir : new String[] { "js", "css", "images", "webjars" }) {
            for (String loc : MvcConfig.locationsUnder(dir, "file:/repo/static/")) {
                assertTrue(loc.endsWith("/" + dir + "/"), loc + " must end with the directory it serves");
            }
        }
    }
}
