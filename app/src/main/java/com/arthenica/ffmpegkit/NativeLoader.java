/*
 * Derived from FFmpegKit 6.0 NativeLoader (LGPL-3.0-or-later).
 * SIG adds support for loading the native runtime from its private component
 * directory instead of requiring the libraries to be embedded in the APK.
 */
package com.arthenica.ffmpegkit;

import android.os.Build;

import java.io.File;
import java.util.Arrays;

public class NativeLoader {

    private static final String NATIVE_DIR_PROPERTY = "sig.native.library.dir";
    static final String[] FFMPEG_LIBRARIES = {
            "avutil", "swscale", "swresample", "avcodec",
            "avformat", "avfilter", "avdevice"
    };

    private static boolean usesDownloadedRuntime() {
        final String directory = System.getProperty(NATIVE_DIR_PROPERTY);
        return directory != null && !directory.trim().isEmpty();
    }

    private static void loadLibrary(final String libraryName) {
        try {
            if (usesDownloadedRuntime()) {
                final File library = new File(
                        System.getProperty(NATIVE_DIR_PROPERTY),
                        System.mapLibraryName(libraryName)
                );
                if (!library.isFile()) {
                    throw new UnsatisfiedLinkError("Biblioteca ausente: " + library.getAbsolutePath());
                }
                System.load(library.getAbsolutePath());
            } else {
                System.loadLibrary(libraryName);
            }
        } catch (final UnsatisfiedLinkError error) {
            throw new Error(
                    String.format("FFmpegKit failed to start on %s.", getDeviceDebugInformation()),
                    error
            );
        }
    }

    static String loadAbi() {
        return AbiDetect.getAbi();
    }

    static String loadPackageName() {
        return Packages.getPackageName();
    }

    static String loadVersion() {
        return FFmpegKitConfig.getVersion();
    }

    static boolean loadIsLTSBuild() {
        return AbiDetect.isNativeLTSBuild();
    }

    static int loadLogLevel() {
        return FFmpegKitConfig.getNativeLogLevel();
    }

    static String loadBuildDate() {
        return FFmpegKitConfig.getBuildDate();
    }

    static void enableRedirection() {
        FFmpegKitConfig.enableRedirection();
    }

    static void loadFFmpegKitAbiDetect() {
        if (usesDownloadedRuntime()) loadLibrary("c++_shared");
        loadLibrary("ffmpegkit_abidetect");
    }

    static boolean loadFFmpeg() {
        if (usesDownloadedRuntime()) {
            loadLibrary("c++_shared");
            for (final String library : FFMPEG_LIBRARIES) loadLibrary(library);
        }
        // minSdk is 24; Android resolves packaged transitive libraries itself.
        return false;
    }

    static void loadFFmpegKit(final boolean armV7aNeonLoaded) {
        loadLibrary("ffmpegkit");
    }

    static String getDeviceDebugInformation() {
        final StringBuilder result = new StringBuilder();
        result.append("brand:").append(Build.BRAND);
        result.append(", model:").append(Build.MODEL);
        result.append(", device:").append(Build.DEVICE);
        result.append(", api level:").append(Build.VERSION.SDK_INT);
        result.append(", abis:").append(Arrays.toString(Build.SUPPORTED_ABIS));
        return result.toString();
    }
}
