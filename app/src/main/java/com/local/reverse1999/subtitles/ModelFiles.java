package com.local.reverse1999.subtitles;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class ModelFiles {
    private ModelFiles() {}

    static synchronized File load(Context context, String directory, String name) throws IOException {
        File folder = new File(context.getFilesDir(), directory);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create model directory");
        File target = new File(folder, name);
        if (target.isFile() && target.length() > 0) return target;
        File pending = File.createTempFile("model-", ".part", folder);
        try {
            try (InputStream input = context.getAssets().open(directory + "/" + name);
                 FileOutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
            }
            if (!pending.renameTo(target)) throw new IOException("Cannot store model " + name);
            return target;
        } finally {
            if (pending.exists()) pending.delete();
        }
    }
}
