package com.fourzerofourgb.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * The background People runner's origin: which bundled files a request path
 * maps to, and the content types they are served with. Pure Java, like
 * JsJsonTest.
 */
public class FaceRunnerAssetsTest {

    @Test
    public void pathsWithAnExtensionAreFiles() {
        assertEquals(Collections.singletonList("runner/faces.html"), FaceRunnerAssets.candidates("runner/faces.html"));
        assertEquals(
            Collections.singletonList("_next/static/chunks/app/(app)/page-1a2b.js"),
            FaceRunnerAssets.candidates("_next/static/chunks/app/(app)/page-1a2b.js")
        );
        assertEquals(Collections.singletonList("faces/models/face_recognition_model.bin"), FaceRunnerAssets.candidates("faces/models/face_recognition_model.bin"));
        // The handler for "/" passes paths without their leading slash; tolerate one anyway.
        assertEquals(Collections.singletonList("faces/wasm/tfjs-backend-wasm.wasm"), FaceRunnerAssets.candidates("/faces/wasm/tfjs-backend-wasm.wasm"));
    }

    @Test
    public void pagesWithoutAnExtensionTryHtmlThenIndex() {
        assertEquals(Arrays.asList("runner/faces.html", "runner/faces/index.html"), FaceRunnerAssets.candidates("runner/faces"));
        assertEquals(Collections.singletonList("runner/faces/index.html"), FaceRunnerAssets.candidates("runner/faces/"));
        assertEquals(Collections.singletonList("index.html"), FaceRunnerAssets.candidates(""));
        assertEquals(Collections.singletonList("index.html"), FaceRunnerAssets.candidates("/"));
        assertEquals(Collections.singletonList("index.html"), FaceRunnerAssets.candidates(null));
    }

    @Test
    public void pathsThatCouldLeaveTheExportAreRefused() {
        assertTrue(FaceRunnerAssets.candidates("../capacitor.config.json").isEmpty());
        assertTrue(FaceRunnerAssets.candidates("runner/../../capacitor.plugins.json").isEmpty());
        assertTrue(FaceRunnerAssets.candidates("runner/./faces.html").isEmpty());
        assertTrue(FaceRunnerAssets.candidates("runner//faces.html").isEmpty());
        assertTrue(FaceRunnerAssets.candidates("runner\\faces.html").isEmpty());
        assertTrue(FaceRunnerAssets.candidates("runner/faces.html\0.png").isEmpty());
        assertTrue(FaceRunnerAssets.candidates("..").isEmpty());
    }

    @Test
    public void contentTypesFollowTheExtension() {
        assertEquals("text/html", FaceRunnerAssets.mimeType("runner/faces.html"));
        assertEquals("text/javascript", FaceRunnerAssets.mimeType("_next/static/chunks/faces.worker.3f9c.js"));
        assertEquals("text/css", FaceRunnerAssets.mimeType("_next/static/css/app.css"));
        assertEquals("application/json", FaceRunnerAssets.mimeType("faces/models/tiny_face_detector_model-weights_manifest.json"));
        assertEquals("application/wasm", FaceRunnerAssets.mimeType("faces/wasm/tfjs-backend-wasm-simd.wasm"));
        assertEquals("application/octet-stream", FaceRunnerAssets.mimeType("faces/models/face_recognition_model.bin"));
        assertEquals("font/woff2", FaceRunnerAssets.mimeType("_next/static/media/unbounded.WOFF2"));
        assertEquals("text/plain", FaceRunnerAssets.mimeType("runner/faces.txt"));
        assertEquals("image/svg+xml", FaceRunnerAssets.mimeType("icons/mark.svg"));
        // A dot in a folder name is no extension.
        assertEquals("application/octet-stream", FaceRunnerAssets.mimeType("v1.2/LICENSE"));
    }

    @Test
    public void textTypesGetAnEncoding() {
        assertTrue(FaceRunnerAssets.isText("text/html"));
        assertTrue(FaceRunnerAssets.isText("text/javascript"));
        assertTrue(FaceRunnerAssets.isText("application/json"));
        assertTrue(FaceRunnerAssets.isText("application/manifest+json"));
        assertTrue(FaceRunnerAssets.isText("image/svg+xml"));
        assertFalse(FaceRunnerAssets.isText("application/wasm"));
        assertFalse(FaceRunnerAssets.isText("image/webp"));
        assertFalse(FaceRunnerAssets.isText("application/octet-stream"));
    }

    @Test
    public void onlyGitHubsApiIsReachable() {
        assertTrue(FaceRunnerAssets.isGitHubApi("api.github.com"));
        assertTrue(FaceRunnerAssets.isGitHubApi("objects.githubusercontent.com"));
        assertFalse(FaceRunnerAssets.isGitHubApi("github.com"));
        assertFalse(FaceRunnerAssets.isGitHubApi("api.github.com.example.org"));
        assertFalse(FaceRunnerAssets.isGitHubApi("githubusercontent.com.example.org"));
        assertFalse(FaceRunnerAssets.isGitHubApi("localhost"));
    }

    @Test
    public void plannedEndsAreNoError() {
        assertTrue(FaceRunnerHost.isPlannedEnd("done"));
        assertTrue(FaceRunnerHost.isPlannedEnd("deadline"));
        assertTrue(FaceRunnerHost.isPlannedEnd("stopped"));
        assertFalse(FaceRunnerHost.isPlannedEnd("GitHub refused the token"));
        assertFalse(FaceRunnerHost.isPlannedEnd(null));
    }
}
