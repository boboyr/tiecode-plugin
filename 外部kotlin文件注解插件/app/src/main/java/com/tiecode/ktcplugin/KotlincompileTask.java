package com.tiecode.ktcplugin;
import com.rapid.api.project.task.BaseTask;
import com.rapid.api.project.task.exception.TaskInterruptError;
import java.io.File;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import java.util.ArrayList;
import com.example.tds.Mcollector;
import org.jetbrains.kotlin.config.Services;
import com.rapid.api.Platform;
import java.nio.file.*;
import java.util.stream.Stream;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import com.rapid.api.ext.plugin.AndroidPlugins;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;
import java.io.OutputStream;
import com.rapid.api.framework.project.ProjectServiceKeys;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;

public class KotlincompileTask extends BaseTask {
	@Override
	public void execute() throws TaskInterruptError {
		File KotlinDir = new File(Platform.getProject().getTasks().getTask("compileWithTiecodec").getIntermediatePath("src"));
		for(String str : findKotlin(KotlinDir.getPath())){
			compilerkotlin(str);
		}
	}

	@Override
	public String getId() {
		return "compileWithKotlinc";
	}

	@Override
	public String getName() {
		return "编译外部kotlin文件";
	}
	
    public void compilerkotlin(String KotlinPath){
		File file = new File(AndroidPlugins.getPluginDir(this), "kt_home");
		if(!file.exists()) file.mkdir();
		File file2 = new File(Platform.getProject().getTasks().getTask("compileWithKotlinc").getIntermediatePath());
		File classpath1 = new File(AndroidPlugins.getPluginDir(this),"classpath/kotlin-stdlib-2.0.0.jar");
		File classpath2 = new File(AndroidPlugins.getPluginDir(this),"classpath/kotlin-stdlib-common-2.0.0.jar");
		File rtJar = new File(AndroidPlugins.getPluginDir(this),"classpath/rt.jar");
		MessageCollector mcollector = new Mcollector();
		K2JVMCompiler k2 = new K2JVMCompiler();
		K2JVMCompilerArguments k2JVMCompilerArguments = new K2JVMCompilerArguments();
		AndroidSdk a2 = Platform.getService(ProjectServiceKeys.TOOLCHAIN).getTool(Tool.ANDROID_SDK);
		k2JVMCompilerArguments.setUseJavac(false);
		k2JVMCompilerArguments.setCompileJava(false);
		k2JVMCompilerArguments.setNewInference(true);
		k2JVMCompilerArguments.setUseFirLT(true);
		k2JVMCompilerArguments.setUseFirIC(true);
		k2JVMCompilerArguments.setIncludeRuntime(false);
		k2JVMCompilerArguments.setNoJdk(true);
		k2JVMCompilerArguments.setNoReflect(true);
		k2JVMCompilerArguments.setNoStdlib(true);
		k2JVMCompilerArguments.setSuppressWarnings(false);
		k2JVMCompilerArguments.setKotlinHome(file.getPath());
		k2JVMCompilerArguments.setDestination(file2.getPath());
		ArrayList<String> arrayList = new ArrayList<String>();
		arrayList.add("-cp");
		arrayList.add(a2.getAndroidJarPath() + ":" + a2.getLambdaCorePath() + ":" + classpath1.getPath() + ":"  + classpath2.getPath());
		arrayList.add(KotlinPath);
		k2.parseArguments(arrayList.toArray(new String[0]),k2JVMCompilerArguments);
		k2.exec(mcollector,Services.EMPTY,k2JVMCompilerArguments);
	}
		public ArrayList<String> findKotlin(String dir) {
			final ArrayList<String> kotlinFiles = new ArrayList<String>();
			Path startPath = Paths.get(dir);

			try {
				Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {
						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
							if (file.toString().endsWith(".kt")) {
								kotlinFiles.add(file.toString());
							}
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
							throw exc;
						}
					});
			} catch (IOException e) {
				throw new RuntimeException("Error walking the file tree: " + e.getMessage(), e);
			}

			return kotlinFiles;
		}
}
