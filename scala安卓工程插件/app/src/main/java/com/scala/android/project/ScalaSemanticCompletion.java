package com.scala.android.project;

import com.scala.android.compiler.ScalaAnalysisApi;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 类型级语义补全：走 scala-android-compiler 里封装好的
 * {@link ScalaAnalysisApi}（dotty 的 {@code InteractiveDriver}）。
 *
 * <h3>为什么不再"用上一轮结果"</h3>
 * 早先的写法是"消费上一次分析结果 + 提交下一轮"，看起来不卡，但结果对应的是<b>上一次</b>
 * 光标位置，于是：
 * <ul>
 *   <li>刚敲完 {@code Toast.} 时拿到的是"{@code Toast}"这一轮的结果 → 弹窗里只有 Toast；</li>
 *   <li>再敲一个字母 {@code m}，拿到的才是 {@code Toast.} 那一轮的成员 → 这时方法才出现。</li>
 * </ul>
 * 补全本来就是宿主在<b>工作线程</b>上调用的（{@code Language.requireAutoComplete}
 * 标了 {@code @WorkerThread}），所以这里改成"要当前光标位置的候选"：
 * <ul>
 *   <li>同位置重复请求走缓存，立刻返回；</li>
 *   <li>driver 还没为该 classpath 建好（冷启动要几秒）时，后台预热、本次先不给语义候选，
 *       绝不阻塞补全；</li>
 *   <li>热了之后单次分析几百毫秒，同步算完直接返回，结果永远对应当前位置。</li>
 * </ul>
 */
public final class ScalaSemanticCompletion {

    private static final ScalaSemanticCompletion INSTANCE = new ScalaSemanticCompletion();

    public static ScalaSemanticCompletion get() {
        return INSTANCE;
    }

    private final ExecutorService warmupExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "scala-semantic-warmup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean warmingUp = new AtomicBoolean(false);

    /** 缓存：键是「文件 + 偏移 + 内容 hash」，只用于完全相同的请求。 */
    private volatile String cachedKey;
    private volatile List<ScalaAnalysisApi.Suggestion> cached = Collections.emptyList();

    private ScalaSemanticCompletion() {
    }

    public boolean isEnabled() {
        return true;
    }

    /**
     * 取<b>当前</b>光标位置的语义候选。
     *
     * @param classpath 编译/补全用的 classpath（含工程依赖）
     */
    public List<ScalaAnalysisApi.Suggestion> completeNow(
            String source, URI uri, int offset, List<File> classpath) {
        if (source == null || uri == null || classpath == null) {
            return Collections.emptyList();
        }
        String key = uri + "#" + offset + "#" + source.hashCode();
        if (key.equals(cachedKey)) {
            return cached;
        }
        if (!ScalaAnalysisApi.isWarm(classpath)) {
            warmUp(classpath);
            return Collections.emptyList();
        }
        List<ScalaAnalysisApi.Suggestion> result;
        try {
            result = ScalaAnalysisApi.complete(source, uri, offset, classpath);
        } catch (Throwable ignored) {
            result = Collections.emptyList();
        }
        cachedKey = key;
        cached = result;
        return result;
    }

    /** 后台把 dotty driver 建出来（同一时间只跑一个）。 */
    private void warmUp(List<File> classpath) {
        if (!warmingUp.compareAndSet(false, true)) {
            return;
        }
        warmupExecutor.execute(() -> {
            try {
                ScalaAnalysisApi.warmUp(classpath);
            } finally {
                warmingUp.set(false);
            }
        });
    }
}
