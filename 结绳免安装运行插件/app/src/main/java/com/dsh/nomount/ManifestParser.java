package com.dsh.nomount;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读懂未安装 APK 的 AndroidManifest.xml（二进制 AXML），把 MAIN/LAUNCHER 那个窗口找出来。
 *
 * <p>用公开 API 拿不到未安装 APK 的 intent-filter（{@code getPackageArchiveInfo} 只给 Activity 名，
 * 不给过滤器），所以要自己解 AXML。只解 START_ELEMENT/END_ELEMENT 和字符串池，够用且不依赖 hidden API。
 */
public final class ManifestParser {

    private static final String TAG = "DSHNoMount";

    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML_START_ELEMENT = 0x0102;
    private static final int RES_XML_END_ELEMENT = 0x0103;
    private static final int TYPE_STRING = 0x03;
    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    public static final class Component {
        public final String name;
        public final Set<String> actions = new LinkedHashSet<>();
        public final Set<String> categories = new LinkedHashSet<>();

        Component(String name) {
            this.name = name;
        }
    }

    private ManifestParser() {
    }

    /** 返回清单里所有 <activity>（含它们的 action/category）。 */
    public static List<Component> activities(File apk) throws IOException {
        byte[] data;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                throw new IOException("APK 里没有 AndroidManifest.xml");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                data = readAll(in);
            }
        }
        return parse(data);
    }

    /** 找 MAIN + LAUNCHER 的窗口；找不到就退化成第一个 Activity。 */
    public static String findLauncher(File apk, String packageName) {
        try {
            List<Component> list = activities(apk);
            for (Component c : list) {
                if (c.name != null
                        && c.actions.contains("android.intent.action.MAIN")
                        && c.categories.contains("android.intent.category.LAUNCHER")) {
                    String full = qualify(c.name, packageName);
                    Log.i(TAG, "清单里的启动窗口: " + full);
                    return full;
                }
            }
            if (!list.isEmpty() && list.get(0).name != null) {
                String full = qualify(list.get(0).name, packageName);
                Log.w(TAG, "没有 MAIN/LAUNCHER，退回第一个 Activity: " + full);
                return full;
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析清单失败", t);
        }
        return null;
    }

    /** 清单里写 ".MainActivity" 这种相对名，要补上包名才是真正的类名。 */
    private static String qualify(String name, String packageName) {
        if (name.startsWith(".") && packageName != null) {
            return packageName + name;
        }
        return name;
    }

    private static List<Component> parse(byte[] d) {
        List<Component> out = new ArrayList<>();
        String[] pool = null;
        List<String> elementStack = new ArrayList<>();
        Component current = null;
        int p = 8; // 跳过 RES_XML_TYPE 头
        while (p + 8 <= d.length) {
            int type = u16(d, p);
            int headerSize = u16(d, p + 2);
            int size = i32(d, p + 4);
            if (size <= 0 || p + size > d.length) {
                break;
            }
            if (type == RES_STRING_POOL) {
                pool = readStringPool(d, p);
            } else if (type == RES_XML_START_ELEMENT && pool != null) {
                int header = p + headerSize;
                String name = str(pool, i32(d, header + 4));
                int attrCount = u16(d, header + 12);
                int attrOff = header + u16(d, header + 8);
                elementStack.add(name == null ? "" : name);
                String activityName = null;
                String action = null;
                String category = null;
                for (int i = 0; i < attrCount; i++) {
                    int base = attrOff + i * 20;
                    if (base + 20 > d.length) {
                        break;
                    }
                    String attrNs = str(pool, i32(d, base));
                    String attrName = str(pool, i32(d, base + 4));
                    int rawValue = i32(d, base + 8);
                    int dataType = d[base + 15] & 0xFF;
                    int value = i32(d, base + 16);
                    if (!"name".equals(attrName)) {
                        continue;
                    }
                    String text = null;
                    if (dataType == TYPE_STRING) {
                        text = str(pool, value);
                    } else if (rawValue >= 0) {
                        text = str(pool, rawValue);
                    }
                    if (text == null) {
                        continue;
                    }
                    if (NS_ANDROID.equals(attrNs) || attrNs == null) {
                        String owner = elementStack.get(elementStack.size() - 1);
                        if ("activity".equals(owner)) {
                            activityName = text;
                        } else if ("action".equals(owner)) {
                            action = text;
                        } else if ("category".equals(owner)) {
                            category = text;
                        }
                    }
                }
                if ("activity".equals(name)) {
                    current = new Component(activityName);
                    out.add(current);
                } else if (current != null) {
                    if ("action".equals(name) && action != null) {
                        current.actions.add(action);
                    } else if ("category".equals(name) && category != null) {
                        current.categories.add(category);
                    }
                }
            } else if (type == RES_XML_END_ELEMENT) {
                int header = p + headerSize;
                String name = pool == null ? null : str(pool, i32(d, header + 4));
                if (!elementStack.isEmpty()) {
                    elementStack.remove(elementStack.size() - 1);
                }
                if ("activity".equals(name)) {
                    current = null;
                }
            }
            p += size;
        }
        return out;
    }

    private static String[] readStringPool(byte[] d, int p) {
        int headerSize = u16(d, p + 2);
        int count = i32(d, p + 8);
        int flags = i32(d, p + 16);
        int stringsStart = i32(d, p + 20);
        boolean utf8 = (flags & 0x100) != 0;
        int offsets = p + headerSize;
        int dataStart = p + stringsStart;
        String[] pool = new String[Math.max(count, 0)];
        for (int i = 0; i < count; i++) {
            int off = dataStart + i32(d, offsets + i * 4);
            pool[i] = utf8 ? readUtf8(d, off) : readUtf16(d, off);
        }
        return pool;
    }

    private static String readUtf8(byte[] d, int off) {
        int len = d[off] & 0xFF;
        off++;
        if ((len & 0x80) != 0) {
            len = ((len & 0x7F) << 8) | (d[off] & 0xFF);
            off++;
        }
        int bytes;
        int b0 = d[off] & 0xFF;
        off++;
        if ((b0 & 0x80) != 0) {
            bytes = ((b0 & 0x7F) << 8) | (d[off] & 0xFF);
            off++;
        } else {
            bytes = b0;
        }
        return new String(d, off, bytes, StandardCharsets.UTF_8);
    }

    private static String readUtf16(byte[] d, int off) {
        int len = u16(d, off);
        off += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | u16(d, off);
            off += 2;
        }
        return new String(d, off, len * 2, StandardCharsets.UTF_16LE);
    }

    private static String str(String[] pool, int index) {
        if (pool == null || index < 0 || index >= pool.length) {
            return null;
        }
        return pool[index];
    }

    private static int u16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
