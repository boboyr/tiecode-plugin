package com.scala.android.tool;

import java.io.File;
import java.util.List;

/**
 * Scala 编译参数（对应 scala-android-compiler 里 ScalaCompilerApi.compile 的三个入参）。
 */
public class ScalaCompileParams {

    /** 待编译的 .scala 源文件。 */
    public List<File> sourceFiles;

    /** class 输出目录。 */
    public File outputDirectory;

    /** 编译 classpath（android.jar、scala-library ...）。 */
    public List<File> classpath;
}
