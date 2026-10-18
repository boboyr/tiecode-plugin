package com.tiecode.aarplugin.compiler;

import com.tiecode.compiler.api.descriptor.Name;
import com.tiecode.compiler.toolchain.processor.BaseNameGenerator;

/**
 * 原名名称生成器：类 / 方法 / 事件 / 变量 / 包名都按结绳源码里的原名输出。
 *
 * <p>接管的是宿主编译器的 {@code NameGenerator}（见宿主
 * {@code AndroidTiecodecCompiler.beforeCompile} 里的
 * {@code AndroidNameGenerator.instance(context)}：
 * 上下文里已经有生成器就直接用，不会覆盖），所以只要在编译前把它注册进
 * {@code Context}，宿主自己的「编译结绳源文件」任务就会按原名产出 Java。
 *
 * <p>几个细节：
 * <ul>
 *   <li>{@code randName()}（无参，给 foreach/交换变量用的临时名）保持随机，
 *       否则同名临时变量会互相冲突；</li>
 *   <li>{@code randCacheName} 按 {@link Name} 缓存，保证「虚拟方法/属性 getter-setter」
 *       这些本来就要同名的符号仍然同名；</li>
 *   <li>名字不是合法 Java 标识符时（比如运算符重载的 {@code []}、{@code +}）回退到
 *       父类的随机命名，避免产出编译不过的 Java；</li>
 *   <li>应用包名不在这里拼音化，由 {@link OriginalNameCompilerPlugin} 提前塞进缓存
 *       （宿主 AndroidTiecodecCompiler 也是这么干的），保证 AndroidManifest/aapt2 拿到的
 *       包名是 ASCII。</li>
 * </ul>
 */
public class OriginalNameGenerator extends BaseNameGenerator {

    @Override
    public String randName(Name originName) {
        String name = originalName(originName);
        return name != null ? name : super.randName(originName);
    }

    @Override
    public String randCacheName(Name originName) {
        String cached = getCacheName(originName);
        if (cached != null) {
            return cached;
        }
        String name = originalName(originName);
        if (name == null) {
            name = super.randCacheName(originName);
        }
        putCacheName(originName, name);
        return name;
    }

    @Override
    public String randOperatorName(Name originName) {
        String cached = getCacheName(originName);
        if (cached != null) {
            return cached;
        }
        String name = originalName(originName);
        if (name == null) {
            return super.randOperatorName(originName);
        }
        putCacheName(originName, name);
        return name;
    }

    @Override
    public String randPackage(Name originName) {
        // 已被预置（应用包名的拼音）或之前算过的直接复用
        String cached = getCacheName(originName);
        if (cached != null) {
            return cached;
        }
        String name = originName == null ? null : originName.toString();
        String result = isValidPackage(name) ? name : super.randPackage(originName);
        putCacheName(originName, result);
        return result;
    }

    @Override
    public String randFullName(String pkg, String className) {
        return pkg + "." + className;
    }

    /** 原名；不是合法标识符时返回 null 交给父类随机命名。 */
    private String originalName(Name originName) {
        if (originName == null) {
            return null;
        }
        String name = originName.toString();
        return isValidIdentifier(name) ? name : null;
    }

    public static boolean isValidIdentifier(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidPackage(String value) {
        if (value == null || value.indexOf('.') < 0 || value.contains(" ")) {
            return false;
        }
        String[] parts = value.split("\\.");
        for (String part : parts) {
            if (!isValidIdentifier(part)) {
                return false;
            }
        }
        return true;
    }
}
