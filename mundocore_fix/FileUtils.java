package com.mundo.utils;

import java.io.File;

public class FileUtils {
    public static boolean mkdirs(File dir) {
        return dir != null && (dir.exists() || dir.mkdirs());
    }
}
