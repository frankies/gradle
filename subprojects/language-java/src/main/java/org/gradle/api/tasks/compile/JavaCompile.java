/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks.compile;

import com.google.common.collect.ImmutableList;
import org.gradle.api.Incubating;
import org.gradle.api.JavaVersion;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.changedetection.changes.StatefulIncrementalTaskInputs;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.JavaToolChainFactory;
import org.gradle.api.internal.tasks.compile.CleaningJavaCompiler;
import org.gradle.api.internal.tasks.compile.CompileJavaBuildOperationReportingCompiler;
import org.gradle.api.internal.tasks.compile.CompilerForkUtils;
import org.gradle.api.internal.tasks.compile.DefaultJavaCompileSpec;
import org.gradle.api.internal.tasks.compile.DefaultJavaCompileSpecFactory;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.internal.tasks.compile.incremental.IncrementalCompilerFactory;
import org.gradle.api.internal.tasks.compile.incremental.recomp.CompilationSourceDirs;
import org.gradle.api.internal.tasks.compile.incremental.recomp.JavaRecompilationSpecProvider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.jvm.internal.toolchain.JavaToolChainInternal;
import org.gradle.jvm.platform.JavaPlatform;
import org.gradle.jvm.platform.internal.DefaultJavaPlatform;
import org.gradle.jvm.toolchain.JavaToolChain;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.base.internal.compile.CompilerUtil;
import org.gradle.util.SingleMessageLogger;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.concurrent.Callable;

/**
 * Compiles Java source files.
 *
 * <pre class='autoTested'>
 *     apply plugin: 'java'
 *
 *     tasks.withType(JavaCompile) {
 *         //enable compilation in a separate daemon process
 *         options.fork = true
 *     }
 * </pre>
 */
@CacheableTask
public class JavaCompile extends AbstractCompile {
    private final CompileOptions compileOptions;
    private JavaToolChain toolChain;
    private final FileCollection stableSources = getProject().files(new Callable<FileTree[]>() {
        @Override
        public FileTree[] call() {
            return new FileTree[] { getSource(), getSources() };
        }
    });

    public JavaCompile() {
        CompileOptions compileOptions = getServices().get(ObjectFactory.class).newInstance(CompileOptions.class);
        this.compileOptions = compileOptions;
        CompilerForkUtils.doNotCacheIfForkingViaExecutable(compileOptions, getOutputs());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Internal
    public FileTree getSource() {
        return super.getSource();
    }

    /**
     * This method is overwritten by the Android plugin < 3.6.
     * We add it here as hack so we can add the source from that method to stableSources.
     * DO NOT USE!
     *
     * @since 5.7
     */
    @Deprecated
    @Internal
    public FileTree getSources() {
        return getProject().files().getAsFileTree();
    }

    /**
     * Returns the tool chain that will be used to compile the Java source.
     *
     * @return The tool chain.
     */
    @Nested
    public JavaToolChain getToolChain() {
        if (toolChain != null) {
            return toolChain;
        }
        return getJavaToolChainFactory().forCompileOptions(getOptions());
    }

    /**
     * Sets the tool chain that should be used to compile the Java source.
     *
     * @param toolChain The tool chain.
     */
    public void setToolChain(JavaToolChain toolChain) {
        this.toolChain = toolChain;
    }

    @TaskAction
    protected void compile(IncrementalTaskInputs inputs) {
        compile(((StatefulIncrementalTaskInputs) inputs).getInputChanges());
    }

    protected void compile(InputChanges inputs) {
        if (!compileOptions.isIncremental()) {
            compile();
            return;
        }

        DefaultJavaCompileSpec spec = createSpec();
        Compiler<JavaCompileSpec> incrementalCompiler = getIncrementalCompilerFactory().makeIncremental(
            createCompiler(spec),
            getPath(),
            getSource(),
            new JavaRecompilationSpecProvider(((ProjectInternal) getProject()).getFileOperations(), (FileTreeInternal) getSource(), inputs.isIncremental(), () -> inputs.getFileChanges(getStableSources()))
        );
        performCompilation(spec, incrementalCompiler);
    }

    @Inject
    protected IncrementalCompilerFactory getIncrementalCompilerFactory() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected JavaToolChainFactory getJavaToolChainFactory() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void compile() {
        DefaultJavaCompileSpec spec = createSpec();
        spec.setSourceFiles(getSource());
        performCompilation(spec, createCompiler(spec));
    }


    private CleaningJavaCompiler createCompiler(JavaCompileSpec spec) {
        Compiler<JavaCompileSpec> javaCompiler = CompilerUtil.castCompiler(((JavaToolChainInternal) getToolChain()).select(getPlatform()).newCompiler(spec.getClass()));
        return new CleaningJavaCompiler(javaCompiler, getOutputs());
    }

    @Nested
    protected JavaPlatform getPlatform() {
        return new DefaultJavaPlatform(JavaVersion.toVersion(getTargetCompatibility()));
    }

    private void performCompilation(JavaCompileSpec spec, Compiler<JavaCompileSpec> compiler) {
        WorkResult result = new CompileJavaBuildOperationReportingCompiler(this, compiler, getServices().get(BuildOperationExecutor.class)).execute(spec);
        setDidWork(result.getDidWork());
    }

    private DefaultJavaCompileSpec createSpec() {
        final DefaultJavaCompileSpec spec = new DefaultJavaCompileSpecFactory(compileOptions).create();
        spec.setDestinationDir(getDestinationDir());
        spec.setWorkingDir(getProject().getProjectDir());
        spec.setTempDir(getTemporaryDir());
        spec.setCompileClasspath(ImmutableList.copyOf(getClasspath()));
        spec.setAnnotationProcessorPath(compileOptions.getAnnotationProcessorPath() == null ? ImmutableList.of() : ImmutableList.copyOf(compileOptions.getAnnotationProcessorPath()));
        spec.setTargetCompatibility(getTargetCompatibility());
        spec.setSourceCompatibility(getSourceCompatibility());
        spec.setCompileOptions(compileOptions);
        spec.setSourcesRoots(CompilationSourceDirs.inferSourceRoots((FileTreeInternal) getSource()));
        return spec;
    }

    /**
     * Returns the compilation options.
     *
     * @return The compilation options.
     */
    @Nested
    public CompileOptions getOptions() {
        return compileOptions;
    }

    @Override
    @CompileClasspath
    @Incremental
    public FileCollection getClasspath() {
        return super.getClasspath();
    }

    /**
     * Returns the path to use for annotation processor discovery. Returns an empty collection when no processing should be performed, for example when no annotation processors are present in the compile classpath or annotation processing has been disabled.
     *
     * <p>You can specify this path using {@link CompileOptions#setAnnotationProcessorPath(FileCollection)}.
     *
     * <p>This path is always empty when annotation processing is disabled.</p>
     *
     * @since 3.4
     * @deprecated Use {@link CompileOptions#getAnnotationProcessorPath()} instead.
     */
    @Deprecated
    @Internal
    @Nullable
    public FileCollection getEffectiveAnnotationProcessorPath() {
        SingleMessageLogger.nagUserOfReplacedProperty("JavaCompile.effectiveAnnotationProcessorPath", "JavaCompile.options.annotationProcessorPath");
        return compileOptions.getAnnotationProcessorPath();
    }

    /**
     * The sources for incremental change detection.
     *
     * @since 5.7
     */
    @Incubating
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    protected FileCollection getStableSources() {
        return stableSources;
    }
}
