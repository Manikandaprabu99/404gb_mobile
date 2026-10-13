package com.fourzerofourgb.app.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 320/800/1600 px (longest side, never upscaled) WebP previews, matching the
 * web app's thumbnails. The server can't decode HEIC, so these are always made
 * on the phone. Best effort: any failure yields an empty list.
 */
final class Thumbnails {

    static final class Thumb {

        /** "1600", "800" or "320". */
        final String size;
        final byte[] webp;

        Thumb(String size, byte[] webp) {
            this.size = size;
            this.webp = webp;
        }
    }

    private static final String TAG = "404gb.Thumbnails";
    private static final int[] SIZES = { 1600, 800, 320 };
    private static final int QUALITY = 82;

    private Thumbnails() {}

    static List<Thumb> generate(Context context, Uri uri, boolean video, int orientation) {
        Bitmap source = null;
        try {
            source = decode(context, uri, video, orientation, SIZES[0]);
            if (source == null) {
                return Collections.emptyList();
            }
            List<Thumb> thumbs = new ArrayList<>();
            // Step down progressively (1600 -> 800 -> 320) for better filtering.
            Bitmap previous = source;
            for (int size : SIZES) {
                Bitmap scaled = scaleToFit(previous, size);
                byte[] webp = encode(scaled);
                if (webp != null) {
                    thumbs.add(new Thumb(Integer.toString(size), webp));
                }
                if (previous != scaled && previous != source) {
                    previous.recycle();
                }
                previous = scaled;
            }
            if (previous != source) {
                previous.recycle();
            }
            return thumbs;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "No thumbnails for " + uri, e);
            return Collections.emptyList();
        } finally {
            if (source != null) {
                source.recycle();
            }
        }
    }

    /**
     * One preview of at most {@code maxSide} px, made like those above (the
     * background People runner's local thumbnails); null when it can't be made.
     */
    static byte[] single(Context context, Uri uri, boolean video, int orientation, int maxSide) {
        Bitmap source = null;
        Bitmap scaled = null;
        try {
            source = decode(context, uri, video, orientation, maxSide);
            if (source == null) {
                return null;
            }
            scaled = scaleToFit(source, maxSide);
            return encode(scaled);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "No preview for " + uri, e);
            return null;
        } finally {
            if (scaled != null && scaled != source) {
                scaled.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    private static Bitmap decode(Context context, Uri uri, boolean video, int orientation, int maxSide) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaProvider's thumbnailer handles HEIC, EXIF rotation and video frames.
            return context.getContentResolver().loadThumbnail(uri, new Size(maxSide, maxSide), null);
        }
        return video ? legacyVideoFrame(context, uri, maxSide) : legacyImage(context.getContentResolver(), uri, orientation, maxSide);
    }

    private static Bitmap legacyImage(ContentResolver resolver, Uri uri, int orientation, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null || orientation % 360 == 0) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(orientation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    private static Bitmap legacyVideoFrame(Context context, Uri uri, int maxSide) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            long atUs = durationMs > 0 ? Math.min(1_000_000L, durationMs * 1000L / 2) : 0L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide);
            }
            return retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read a video frame from " + uri, e);
            return null;
        } finally {
            releaseQuietly(retriever);
        }
    }

    /** Width, height, rotation (degrees) and duration (ms) from the container, for when MediaStore lacks them. */
    static long[] probeVideo(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            return new long[] {
                parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),
                parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)),
                parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)),
                parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)),
            };
        } catch (RuntimeException e) {
            return new long[] { 0, 0, 0, 0 };
        } finally {
            releaseQuietly(retriever);
        }
    }

    /** Pixel size from the image header (no full decode), or {0, 0}. */
    static int[] probeImage(ContentResolver resolver, Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
        } catch (IOException | RuntimeException e) {
            return new int[] { 0, 0 };
        }
        return new int[] { Math.max(0, bounds.outWidth), Math.max(0, bounds.outHeight) };
    }

    // MediaMetadataRetriever only implements AutoCloseable from API 29, so no try-with-resources.
    private static void releaseQuietly(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (IOException | RuntimeException ignored) {
            // Nothing left to clean up.
        }
    }

    private static Bitmap scaleToFit(Bitmap bitmap, int maxSide) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxSide) {
            return bitmap;
        }
        float scale = maxSide / (float) longest;
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, w, h, true);
    }

    private static byte[] encode(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? Bitmap.CompressFormat.WEBP_LOSSY : legacyWebp();
        if (!bitmap.compress(format, QUALITY, out) || out.size() == 0) {
            return null;
        }
        return out.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static Bitmap.CompressFormat legacyWebp() {
        return Bitmap.CompressFormat.WEBP;
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
