package com.tiecode.ktcplugin;
import com.tiecode.compiler.api.process.AnnotationProcessor;
import com.tiecode.compiler.toolchain.tree.TCTree.TCAnnotation;
import com.tiecode.compiler.toolchain.processor.Levels;
import com.tiecode.compiler.toolchain.type.Type;
import com.tiecode.compiler.api.descriptor.Name;
import com.tiecode.compiler.toolchain.env.Context;
import com.tiecode.compiler.toolchain.type.Types;
import com.tiecode.compiler.toolchain.tree.TCTree.TCAnnotated;
import com.tiecode.compiler.toolchain.tree.TCTree;
import com.tiecode.compiler.util.Names;
import com.rapid.api.Platform;
import com.tiecode.compiler.toolchain.tree.TreeInfo;
import java.io.File;
import com.tiecode.compiler.toolchain.env.FileSearcher;
import com.rapid.util.file.FileUtils;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import java.util.ArrayList;
import com.example.tds.Mcollector;
import org.jetbrains.kotlin.config.Services;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.util.android.AssetUtils;
import com.tiecode.compiler.api.platform.android.AndroidLib;
import com.tiecode.compiler.api.platform.android.AndroidOutput;
import com.tiecode.compiler.api.process.NameGenerator;
import com.tiecode.compiler.toolchain.env.Options;
import java.nio.file.*;
import java.util.stream.Stream;
import java.nio.file.attribute.BasicFileAttributes;


public class KotlinProcessor implements AnnotationProcessor{
    
    public static final Name a = Names.of("外部kotlin文件");

	private static final Pattern b = Pattern.compile("\\s*package\\s+([a-zA-Z0-9._]+)\\s*;?");

	private String file2Path;
	private String packagename;
	@Override
	public Type getArgType(Types types) {
		return types.FILE;
	}

	@Override
	public String getDescription() {
		return null;
	}

	@Override
	public Levels getLevels() {
		Levels levels = new Levels(1);
        return levels;
	}

	@Override
	public int getPeriod() {
		return 3;
	}

	@Override
	public Name[] getSuffixes() {
		return null;
	}

	@Override
	public void process(TCTree.TCAnnotation tCAnnotation, TCTree.TCAnnotated tCAnnotated, Context context) {
		String str = (String) tCAnnotation.getArgConstValue();
        File searchSource = FileSearcher.instance(context).searchSource(TreeInfo.getSourceDir(tCAnnotated), str);
		File file = new File(Platform.getProject().getTasks().getTask("compileWithTiecodec").getIntermediatePath("src"));
		if (searchSource.isDirectory()) {
			for(String str2 : findKotlin(searchSource.getPath())){
				try {
					String f = FileUtils.readFile(str2);
					Matcher matcher = b.matcher(f);
					if (matcher.find()) {
						this.packagename = matcher.group(1);
						this.packagename = packagename.replace(".","/");
						this.file2Path = file + File.separator + this.packagename + File.separator + new File(str2).getName();
						FileUtils.copyFile(str2, this.file2Path);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
		}
		
		else{
			try {
				String f = FileUtils.readFile(searchSource);
				Matcher matcher = b.matcher(f);
				if (matcher.find()) {
					this.packagename = matcher.group(1);
					this.packagename = packagename.replace(".","/");
					this.file2Path = file + File.separator + this.packagename + File.separator + searchSource.getName();
					FileUtils.copyFile(searchSource.getPath(), this.file2Path);
				}
				
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public boolean supportSuffix() {
		return false;
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
