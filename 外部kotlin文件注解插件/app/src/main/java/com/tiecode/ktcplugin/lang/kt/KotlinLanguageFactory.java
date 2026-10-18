package com.tiecode.ktcplugin.lang.kt;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.lang.Language;
import com.rapid.api.lang.factory.TextMateLanguageFactory;

import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Kotlin 语言工厂：把 {@code .kt} 文件关联到语言 id {@code "kotlin"}，
 * 并提供带语法高亮的语言实例（{@code .kts} 见 {@link KotlinScriptLanguageFactory}）。
 *
 * <p>结绳定位语言引擎的链路是：
 * <pre>
 * 扩展名 → 工厂({@code getAssociateFileType})
 *        → 语言 id({@code getLanguageId})
 *        → 引擎({@code LanguageManager.registerEngine})
 * </pre>
 * 所以这个工厂是「补全能被问到」的前提：没有它，{@code .kt} 文件不会被当成
 * Kotlin 语言打开，注册再好的引擎也不会被调用。
 *
 * <h3>两条加载路径</h3>
 * <ol>
 *   <li>首选宿主实现的 {@code TextMateLanguageFactory} 工具方法
 *       （{@code readGrammarSource} / {@code readConfiguration} / {@code getThemeSource}），
 *       与工程内其它插件（{@code GoLanguageFactory}、{@code CppLanguageFactory}）保持一致；</li>
 *   <li>宿主实现不可用时（不同结绳版本 API 基类方法可能缺失），退化为
 *       {@link IGrammarSource#fromInputStream} + {@link InputStreamReader} 自行构造。</li>
 * </ol>
 * 任何一步失败都会记一条日志并返回 {@code null}（纯文本打开）——
 * 高亮缺失是可理解的状态，让编辑器崩溃不是。
 */
public class KotlinLanguageFactory extends TextMateLanguageFactory {

    /** 日志标签；用于定位「高亮/补全没生效」这类静默失败。 */
    static final String TAG = "KotlinLang";

    /** assets 内语法文件位置。 */
    public static final String GRAMMAR_ASSET = "textmate/kotlin/Kotlin.tmLanguage.json";
    public static final String CONFIGURATION_ASSET = "textmate/kotlin/language-configuration.json";

    /**
     * 传给 tm4e 的文件名。
     *
     * <p>tm4e 靠<b>最后一个扩展名</b>判断语法文件是 JSON 还是 plist/XML，
     * 所以这个名字必须与内容格式一致、不能省。
     */
    private static final String GRAMMAR_FILE_NAME = "Kotlin.tmLanguage.json";

    @Override
    public String getLanguageId() {
        return KotlinLanguage.ID;
    }

    @Override
    public String getAssociateFileType() {
        return "kt";
    }

    @Override
    public Language newLanguage() {
        AssetManager assets = assets();
        if (assets == null) {
            Log.w(TAG, "拿不到插件 AssetManager，Kotlin 高亮降级为纯文本");
            return null;
        }
        try {
            IGrammarSource grammar = loadGrammar(assets);
            if (grammar == null) {
                Log.w(TAG, "语法加载失败，Kotlin 高亮降级为纯文本");
                return null;
            }
            Reader configuration = loadConfiguration(assets);
            return new KotlinLanguage(grammar, configuration, loadTheme());
        } catch (Throwable t) {
            // 构造 Language 也放进 try：宿主真实现的构造器无法静态确认是否会抛，
            // 而调用方（BodyPartial）没有 try/catch，抛出去就是编辑器初始化失败。
            Log.w(TAG, "构造 Kotlin 语言失败，降级为纯文本", t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 语法 / 配置加载
    // ------------------------------------------------------------------

    /**
     * 加载语法。
     *
     * <p>优先走<b>文件</b>而不是 AssetManager 的流：{@code IGrammarSource} 是惰性读取的，
     * 语法对象（语言对象）活多久，它持有的 Reader 就得活多久。AssetManager 的流又不该关
     * （关掉会影响后续资源读取），与其让它随打开文件次数累积，不如把语法复制到插件
     * 缓存目录一次，之后都从文件读——生命周期就完全可控了。
     */
    private IGrammarSource loadGrammar(AssetManager assets) throws IOException {
        File local = materializeAsset(assets, GRAMMAR_ASSET, GRAMMAR_FILE_NAME);
        if (local != null) {
            try {
                IGrammarSource source = readGrammarSource(local);
                if (source != null) {
                    return source;
                }
            } catch (Throwable ignored) {
                // 宿主未实现该工具方法，继续往下走。
            }
            try {
                return IGrammarSource.fromFile(local);
            } catch (Throwable ignored) {
                // 文件源也不可用（极旧版本），退回流路径。
            }
        }
        // 兜底：直接用 assets 流。
        InputStream stream = assets.open(GRAMMAR_ASSET);
        try {
            // 刻意不 close：IGrammarSource 惰性读取，提前关掉会让高亮在渲染时失败。
            return IGrammarSource.fromInputStream(stream, GRAMMAR_FILE_NAME, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            close(stream);
            throw t instanceof IOException ? (IOException) t : new IOException(t);
        }
    }

    /** 语言配置（注释符号、括号配对等）。缺省为空 Reader，不影响高亮。 */
    private Reader loadConfiguration(AssetManager assets) {
        InputStream stream = null;
        try {
            stream = assets.open(CONFIGURATION_ASSET);
            Reader reader = readConfiguration(stream);
            if (reader != null) {
                return reader;
            }
        } catch (Throwable ignored) {
            // 宿主未实现，走兜底。
        }
        // 宿主工具方法没用上（或抛异常）：这条流留着也没意义，关掉后重新打开。
        close(stream);
        try {
            return new InputStreamReader(assets.open(CONFIGURATION_ASSET), "UTF-8");
        } catch (Throwable t) {
            return new StringReader("");
        }
    }

    /**
     * 主题来源：始终用宿主实现的 {@code getThemeSource()}。
     *
     * <p>结绳会把当前编辑器主题（TextMateEditorTheme）交给这里，让 Kotlin 的
     * scope 着色跟着用户选择的主题走。宿主实现缺失时返回 {@code null}，
     * sora 的 TextMateLanguage 构造器对 null 主题有守卫，不会崩。
     */
    private IThemeSource loadTheme() {
        try {
            return getThemeSource();
        } catch (Throwable t) {
            Log.w(TAG, "取编辑器主题失败，Kotlin 高亮使用默认配色");
            return null;
        }
    }

    /**
     * 把 assets 里的文件复制到插件缓存目录（已存在且大小一致就复用）。
     *
     * @return 本地文件；插件上下文拿不到或复制失败时返回 {@code null}
     */
    private File materializeAsset(AssetManager assets, String assetPath, String fileName) {
        try {
            Context context = AndroidPlugins.getContext(this);
            if (context == null) {
                return null;
            }
            File dir = new File(context.getCacheDir(), "kt-lang");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File target = new File(dir, fileName);
            InputStream in = assets.open(assetPath);
            try {
                long available = in.available();
                if (target.isFile() && available > 0 && target.length() == available) {
                    return target;
                }
                File temp = new File(dir, fileName + ".tmp");
                FileOutputStream out = new FileOutputStream(temp);
                try {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                } finally {
                    close(out);
                }
                if (target.isFile() && !target.delete()) {
                    return target;
                }
                return temp.renameTo(target) ? target : temp;
            } finally {
                close(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // AssetManager 获取
    // ------------------------------------------------------------------

    /**
     * 取<b>插件自己</b>的 AssetManager。
     *
     * <p>必须用 {@code AndroidPlugins.getContext(this)}：这里的 {@code this} 是插件里的
     * 工厂实例，宿主据此定位到本插件，拿到的 assets 才包含
     * {@code textmate/kotlin/…}。故意不回退到宿主 Activity 的 assets——那里面不可能有
     * 插件资源，回退只会把「拿不到插件上下文」这个真问题掩盖成「加载到错语法」。
     */
    private AssetManager assets() {
        try {
            Context context = AndroidPlugins.getContext(this);
            return context == null ? null : context.getAssets();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void close(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
