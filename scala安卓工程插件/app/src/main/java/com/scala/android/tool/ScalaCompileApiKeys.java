package com.scala.android.tool;

import com.rapid.framework.data.Key;

/**
 * Scala 编译器在宿主工具链服务里的键。
 */
public interface ScalaCompileApiKeys {

    /** 随插件打包的 Scala 编译器版本。 */
    String VERSION = "3.8.1";

    Key<ScalaCompile> SCALA_COMPILE = new Key<>(ScalaCompile.class, "scalaCompile");
}
