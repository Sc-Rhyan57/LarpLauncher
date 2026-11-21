package com.larp.launcher.boot;

public final class GameBoot {

    private static final String[] ORDER = {
        "crypto",
        "ssl",
        "dobby",
        "System.Native",
        "System.Globalization.Native",
        "System.IO.Compression.Native",
        "System.Security.Cryptography.Native.OpenSsl",
        "clrjit",
        "coreclr",
        "fusion"
    };

    private GameBoot() {
    }

    public static String run() {
        StringBuilder report = new StringBuilder();
        report.append("loader: ").append(GameBoot.class.getClassLoader()).append('\n');
        for (String lib : ORDER) {
            try {
                System.loadLibrary(lib);
                report.append("ok: ").append(lib).append('\n');
            } catch (Throwable t) {
                report.append(lib).append(": ").append(String.valueOf(t.getMessage())).append('\n');
            }
        }
        return report.toString();
    }
}
