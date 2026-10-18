package com.scala.android.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dotty.tools.dotc.Main;
import dotty.tools.dotc.interfaces.Diagnostic;
import dotty.tools.dotc.interfaces.SourcePosition;
import dotty.tools.dotc.reporting.Reporter;
import scala.jdk.javaapi.CollectionConverters;

/** Small Java facade around the Scala 3 compiler API. */
public final class ScalaCompilerApi {
    private ScalaCompilerApi() {
    }

    public static Result compile(List<File> sourceFiles, File outputDirectory, List<File> classpath) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one Scala source file is required");
        }
        for (File sourceFile : sourceFiles) {
            if (sourceFile == null || !sourceFile.isFile()) {
                throw new IllegalArgumentException("Scala source file does not exist: " + sourceFile);
            }
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("Output directory is required");
        }
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create output directory: " + outputDirectory);
        }

        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(outputDirectory.getAbsolutePath());
        if (classpath == null || classpath.isEmpty()) {
            args.add("-usejavacp");
        } else {
            args.add("-classpath");
            args.add(joinClasspath(classpath));
        }
        for (File sourceFile : sourceFiles) {
            args.add(sourceFile.getAbsolutePath());
        }

        try {
            Reporter reporter = Main.process(args.toArray(new String[0]));
            List<DiagnosticInfo> diagnostics = new ArrayList<>();
            for (Diagnostic diagnostic : CollectionConverters.asJava(reporter.allErrors())) {
                diagnostics.add(toDiagnosticInfo(diagnostic));
            }
            for (Diagnostic diagnostic : CollectionConverters.asJava(reporter.allWarnings())) {
                diagnostics.add(toDiagnosticInfo(diagnostic));
            }
            return new Result(reporter.errorCount(), reporter.warningCount(), reporter.hasErrors(), diagnostics);
        } catch (Throwable error) {
            throw new IllegalStateException("Scala compiler failed: " + describe(error), error);
        }
    }


    private static DiagnosticInfo toDiagnosticInfo(Diagnostic diagnostic) {
        Optional<SourcePosition> optional = diagnostic.position();
        if (optional.isPresent()) {
            SourcePosition position = optional.get();
            String path = position.source() == null ? "" : position.source().path();
            return new DiagnosticInfo(diagnostic.level(), diagnostic.message(), path,
                    position.startLine() + 1, position.startColumn() + 1);
        }
        return new DiagnosticInfo(diagnostic.level(), diagnostic.message(), "", 0, 0);
    }

    private static String joinClasspath(List<File> files) {
        StringBuilder result = new StringBuilder();
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (result.length() > 0) {
                result.append(File.pathSeparatorChar);
            }
            result.append(file.getAbsolutePath());
        }
        return result.toString();
    }

    private static String describe(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (result.length() > 0) {
                result.append("\nCaused by: ");
            }
            result.append(current.getClass().getName());
            if (current.getMessage() != null) {
                result.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return result.toString();
    }

    public static final class DiagnosticInfo {
        public final int level;
        public final String message;
        public final String sourcePath;
        public final int line;
        public final int column;

        private DiagnosticInfo(int level, String message, String sourcePath, int line, int column) {
            this.level = level;
            this.message = message;
            this.sourcePath = sourcePath;
            this.line = line;
            this.column = column;
        }
    }

    public static final class Result {
        public final int errorCount;
        public final int warningCount;
        public final boolean failed;
        public final List<DiagnosticInfo> diagnostics;

        private Result(int errorCount, int warningCount, boolean failed, List<DiagnosticInfo> diagnostics) {
            this.errorCount = errorCount;
            this.warningCount = warningCount;
            this.failed = failed;
            this.diagnostics = diagnostics;
        }

        public boolean isSuccess() {
            return !failed;
        }
    }
}
