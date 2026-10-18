package com.goproject.plugin;
import java.util.List;
import java.io.File;
import java.util.ArrayList;

public class FileUtils {
    
    public static List<String> getGofile(File path) {
        File[] listFiles = new File(path.getPath()).listFiles();
        if (listFiles == null) {
            return null;
        }
        ArrayList arrayList = new ArrayList();
        for (File file : listFiles) {
            if (file.getName().endsWith(".go")) {
                arrayList.add(file.getPath());
            }
        }
        return arrayList;
    }
    
}
