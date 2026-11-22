package com.local.reverse1999.subtitles;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class EnglishModel {
    private static final String MODEL = "vosk-model-small-en-us-0.15";

    private EnglishModel() {}

    static synchronized File prepare(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), MODEL);
        File marker = new File(directory, ".ready");
        if (marker.isFile()) return directory;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create English model directory");
        }
        String prefix = directory.getCanonicalPath() + File.separator;
        try (ZipInputStream archive = new ZipInputStream(context.getAssets().open(MODEL + ".zip"))) {
            for (ZipEntry entry; (entry = archive.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                File output = new File(context.getFilesDir(), entry.getName());
                if (!output.getCanonicalPath().startsWith(prefix)) {
                    throw new IOException("Unexpected English model archive path");
                }
                File parent = output.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create model subdirectory");
                }
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = archive.read(buffer)) != -1; ) stream.write(buffer, 0, read);
                }
            }
        }
        if (!new File(directory, "am/final.mdl").isFile() || !marker.createNewFile()) {
            throw new IOException("Bundled English model is incomplete");
        }
        return directory;
    }
}
