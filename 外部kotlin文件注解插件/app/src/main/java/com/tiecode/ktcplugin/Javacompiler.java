package com.tiecode.ktcplugin;
import com.rapid.toolchain.java.BaseJavaCompiler;
import com.rapid.api.project.toolchain.java.JavacParams;
import com.rapid.api.project.toolchain.Tool.OnExecutionCallback;
import com.rapid.api.project.toolchain.Tool;
import java.util.Iterator;
import java.io.File;
import com.sun.tools.javac.main.Main;
import com.tiecode.compiler.api.platform.android.AndroidLib;
import com.rapid.toolchain.java.JavacOutputLogger;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.OutputStream;
import com.rapid.api.Platform;
import com.rapid.util.file.FileUtils;
import java.io.StringWriter;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.Collection;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.ArrayList;

public class Javacompiler extends BaseJavaCompiler {

	@Override
	public boolean compile(JavacParams javacParams, Tool.OnExecutionCallback onExecutionCallback) {
		File file = new File(Platform.getProject().getTasks().getTask("compileWithKotlinc").getIntermediatePath());
		javacParams.classPaths.add(file.getPath());
		File file2 = new File(Platform.getProject().getTasks().getTask("compileWithJavac").getIntermediatePath());
		
		Iterator it = collectClassFiles(file).iterator();
		
		while(it.hasNext()){
			String str = it.next().toString();
			try {
				FileUtils.copyFile(str, file2 + str.substring(file.getAbsolutePath().length()));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		String[] strArr = toJavacArgs(javacParams).toArray(new String[0]);
        JavacOutputLogger javacOutputLogger = new JavacOutputLogger(onExecutionCallback);
        Main.Result compile = new Main("javac", new PrintWriter((OutputStream) javacOutputLogger, true)).compile(strArr);
        try {
            javacOutputLogger.close();
        } catch (IOException unused) {
        }
        return compile.isOK();
	}

	@Override
	public String getCompilerVersion() {
		return "1.8";
	}
	
	public Collection<Path> collectClassFiles(File directory) {
        List<Path> classFiles = new ArrayList<>();
        collectClassFilesRecursively(directory, classFiles);
        return classFiles;
    }

    private void collectClassFilesRecursively(File file, List<Path> classFiles) {
        // 检查是否为目录
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    collectClassFilesRecursively(f, classFiles); // 递归调用
                }
            }
        } else if (file.getName().endsWith(".class")) { // 检查是否为 .class 文件
            classFiles.add(file.toPath()); // 添加 .class 文件路径
        }
    }
	
}
