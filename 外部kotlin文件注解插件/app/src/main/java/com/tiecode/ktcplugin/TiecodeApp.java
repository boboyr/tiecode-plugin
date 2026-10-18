package com.tiecode.ktcplugin;
import android.util.Log;

import com.rapid.framework.program.android.app.AndroidPlugin;
import com.rapid.api.framework.ServiceKeys;
import com.tiecode.compiler.toolchain.CompilerPlugins;
import com.tiecode.compiler.api.plugin.CompilerPlugin;
import com.tiecode.compiler.api.Compiler;
import com.rapid.api.framework.project.ProjectServiceKeys;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.lang.engine.LanguageEngine;
import com.rapid.api.lang.factory.LanguageFactory;
import com.rapid.api.project.service.LanguageManager;
import com.tiecode.ktcplugin.lang.kt.KotlinLanguage;
import com.tiecode.ktcplugin.lang.kt.KotlinLanguageEngine;
import com.tiecode.ktcplugin.lang.kt.KotlinLanguageFactory;
import com.tiecode.ktcplugin.lang.kt.KotlinScriptLanguageFactory;

public class TiecodeApp extends AndroidPlugin {

    /** 与 {@code KotlinLanguageFactory.TAG} 保持一致，便于一起过滤日志。 */
    private static final String TAG = "KotlinLang";

    @Override
    public void activate() {
        getService(ProjectServiceKeys.TOOLCHAIN).setTool(Tool.JAVA_COMPILER,new Javacompiler());
        getService(ProjectServiceKeys.TOOLCHAIN).setTool(Tool.D8,new D8CompilerImpl());
        CompilerPlugins.assemble(new CompilerPlugin(){
              
                @Override
                public void apply(Compiler compiler) {
                    compiler.registerAnnotation("外部kotlin文件",new KotlinProcessor());
                }
            });
        registerKotlinLanguage();
    }

    /**
     * 注册 Kotlin 语言工厂与引擎（{@code .kt}/{@code .kts} 的语法高亮 + 补全）。
     *
     * <p><b>刻意只走代码注册，不写 {@code project.language} 扩展点</b>：
     * 结绳的插件激活顺序是「先激活全部扩展点，再调 {@code PluginApplication.activate()}}」
     * （见 {@code PluginAgent.activate}），而扩展点激活一旦抛异常就中断整条流程——
     * 于是本插件原有的编译能力（{@code Tool.JAVA_COMPILER} / {@code Tool.D8} /
     * {@code 外部kotlin文件} 注解）会跟着一起注册不上。给「编辑器锦上添花」的能力
     * 不该有这种连坐代价，所以注册放在本方法里、整体 try-catch，最坏也只是没有高亮补全。
     *
     * <p>注册后立刻自检并打日志：这条链路失败时编辑器只会表现为静默不弹补全，
     * 没有日志基本无从定位。
     */
    private void registerKotlinLanguage() {
        try {
            LanguageManager manager = getService(ProjectServiceKeys.LANGUAGE);
            if (manager == null) {
                Log.w(TAG, "LanguageManager 不可用，Kotlin 高亮与补全未注册");
                return;
            }
            LanguageFactory kt = new KotlinLanguageFactory();
            LanguageFactory kts = new KotlinScriptLanguageFactory();
            manager.registerLanguageFactory(kt);
            manager.registerLanguageFactory(kts);
            LanguageEngine engine = KotlinLanguageEngine.getInstance();
            manager.registerEngine(KotlinLanguage.ID, engine);

            LanguageFactory foundKt = manager.findLanguageFactory("kt");
            LanguageFactory foundKts = manager.findLanguageFactory("kts");
            LanguageEngine foundEngine = manager.getEngine(KotlinLanguage.ID);
            Log.i(TAG, "Kotlin 语言注册自检:"
                    + " kt=" + (foundKt == null ? "未命中!" : foundKt.getClass().getSimpleName())
                    + " kts=" + (foundKts == null ? "未命中!" : foundKts.getClass().getSimpleName())
                    + " engine(" + KotlinLanguage.ID + ")=" + (foundEngine == null ? "未注册!" : "已注册")
                    + " 已注册引擎=" + manager.getEnginesMap().keySet());
        } catch (Throwable t) {
            // 语言能力注册失败不影响本插件原有的编译链路。
            Log.w(TAG, "Kotlin 语言注册失败，将退化为无高亮/无补全", t);
        }
    }

}
