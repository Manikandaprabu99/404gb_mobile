package com.fourzerofourgb.app.backup;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shares the WebView's session cookie with native HTTP calls, so uploads made
 * while the app is closed are authenticated as the signed-in user, and any
 * renewed session cookie the server sends back lands in the WebView too.
 */
final class WebCookies {

    private static final String TAG = "404gb.WebCookies";
    private static final String SESSION_COOKIE = "404gb_session";
    private static volatile CookieManager manager;

    private WebCookies() {}

    private static CookieManager manager() throws IOException {
        CookieManager local = manager;
        if (local != null) {
            return local;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            local = CookieManager.getInstance();
            manager = local;
            return local;
        }
        // A worker may be the first thing in the process to touch WebView;
        // load its cookie store on the main thread, then use it from here.
        final AtomicReference<CookieManager> result = new AtomicReference<>();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result.set(CookieManager.getInstance());
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IOException("Timed out loading the WebView cookie store");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading the WebView cookie store", e);
        }
        if (failure.get() != null) {
            throw new IOException("WebView cookie store is unavailable", failure.get());
        }
        local = result.get();
        manager = local;
        return local;
    }

    /** Value for the Cookie request header, or null when there are no cookies. */
    static String cookieHeader(String url) throws IOException {
        try {
            String cookies = manager().getCookie(url);
            return cookies == null || cookies.isEmpty() ? null : cookies;
        } catch (RuntimeException e) {
            throw new IOException("Could not read WebView cookies", e);
        }
    }

    /**
     * Stores every Set-Cookie header value for {@code url} and flushes them to disk.
     * The session cookie is only replaced if the WebView still holds the one this
     * request was sent with ({@code sentCookieHeader}): a slow upload that finishes
     * after the user signed in again (or out) must not bring back the old session or
     * delete the new one.
     */
    static void store(String url, List<String> setCookieHeaders, String sentCookieHeader) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            return;
        }
        try {
            CookieManager cookies = manager();
            String sentSession = cookieValue(sentCookieHeader, SESSION_COOKIE);
            String currentSession = cookieValue(cookies.getCookie(url), SESSION_COOKIE);
            boolean sessionChanged = sentSession == null ? currentSession != null : !sentSession.equals(currentSession);
            for (String header : setCookieHeaders) {
                if (header == null || header.isEmpty()) {
                    continue;
                }
                if (sessionChanged && header.trim().startsWith(SESSION_COOKIE + "=")) {
                    continue;
                }
                cookies.setCookie(url, header);
            }
            cookies.flush();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not store renewed session cookie", e);
        }
    }

    /** Value of cookie {@code name} in a Cookie header ("a=1; b=2"), or null. */
    private static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String pair = part.trim();
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }
}
