package com.goproject.plugin.structure;

import com.goproject.plugin.lsp.GoplsToolchain;
import com.rapid.api.project.structure.PSFile;
import com.rapid.api.project.structure.PSObject;
import com.rapid.api.project.structure.PSObjectFilter;
import com.rapid.api.project.structure.extension.PSObjectExpansionCase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「External Libraries」标记文件的展开方式（类似 GoLand 的 External Libraries）。
 *
 * <p>工程根目录下的 {@code External Libraries} 标记文件（无扩展名）展开后：
 * <ul>
 *   <li><b>GO SDK &lt;版本&gt;</b> —— GOROOT/src，可浏览 Go 标准库源码；</li>
 *   <li><b>模块缓存里的依赖目录</b> —— {@code $GOMODCACHE} 下的各域名目录
 *       （如 {@code github.com}），逐层点进去就是
 *       {@code github.com/ulikunitz/xz@latest} 这类下载好的依赖包。</li>
 * </ul>
 *
 * <p>注册方式：{@code fileManager.addPSObjectExpansionCase(
 * new ExternalLibrariesExpansionCase(), "External Libraries")}。
 * 关键在于 {@code FileUtils.getFileExtension("External Libraries")}
 * 对无扩展名的文件返回<b>整个名字</b>，所以按整个名字注册就能命中。
 */
public class ExternalLibrariesExpansionCase implements PSObjectExpansionCase {

    /** 从工具链 zip 名（go1.27.1-aarch64.zip）里取版本号。 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("go([0-9][0-9.]*)-");

    @Override
    public boolean sortChildren() {
        return true;
    }

    @Override
    public boolean useCache() {
        // 模块缓存随 go get 变化，每次展开都重新列。
        return false;
    }

    @Override
    public List<? extends PSObject> getChildren(PSObject psObject, List<PSObjectFilter> filters) {
        List<PSObject> children = new ArrayList<>();
        android.content.Context context = GoplsToolchain.pluginContext();
        if (context == null) {
            return children;
        }

        // ---- GO SDK 源码（GOROOT/src）----
        File src = new File(GoplsToolchain.goRoot(context), "src");
        if (src.isDirectory()) {
            children.add(new NamedPSFile(src, "GO SDK " + goSdkVersion()));
        }

        // ---- 模块缓存里的依赖（按域名目录直接展示）----
        File modCache = new File(new File(GoplsToolchain.environmentDir(context), "gopath"), "pkg/mod");
        File[] domains = modCache.listFiles();
        if (domains != null) {
            for (File domain : domains) {
                String name = domain.getName();
                // cache/ 是 go 的下载缓存内部目录，不作为"依赖"展示。
                if (!domain.isDirectory() || name.startsWith(".") || "cache".equals(name)) {
                    continue;
                }
                children.add(new PSFile(domain));
            }
        }
        return children;
    }

    /** 从工具链 zip 名推导显示版本，如 "GO SDK 1.27.1"；取不到就用 1.27.1 兜底。 */
    private static String goSdkVersion() {
        Matcher matcher = VERSION_PATTERN.matcher(GoplsToolchain.ASSET_ZIP);
        return matcher.find() ? matcher.group(1) : "1.27.1";
    }
}
