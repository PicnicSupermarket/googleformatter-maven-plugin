package com.theoryinpractise.googleformatter;

import com.google.common.base.MoreObjects;
import com.google.googlejavaformat.java.Main;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.googlejavaformat.java.JavaFormatterOptions.Style;

/**
 * Reformat all source files using the Google Code Formatter
 */
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class GoogleFormatterMojo extends AbstractMojo {

  @Component ScmManager scmManager;

  @Parameter(required = true, readonly = true, property = "project")
  protected MavenProject project;

  @Parameter(required = true, readonly = true, property = "project.build.sourceDirectory")
  protected File sourceDirectory;

  @Parameter(required = true, readonly = true, property = "project.build.testSourceDirectory")
  protected File testSourceDirectory;

  @Parameter(required = true, readonly = true, property = "project.build.outputDirectory")
  protected File outputDirectory;

  @Parameter(required = true, readonly = true, property = "project.build.testOutputDirectory")
  protected File testOutputDirectory;

  @Parameter(defaultValue = "false")
  protected boolean includeStale;

  @Parameter(defaultValue = "GOOGLE")
  protected Style style;

  @Parameter(defaultValue = "false", property = "formatter.skip")
  protected boolean skip;

  @Parameter(defaultValue = "false", property = "formatter.modified")
  protected boolean filterModified;

  public void execute() throws MojoExecutionException {

    if ("pom".equals(project.getPackaging())) {
      getLog().info("Project packaging is POM, skipping...");
      return;
    }

    if (skip) {
      getLog().info("Skipping source reformatting due to plugin configuration.");
      return;
    }

    try {
      Set<File> sourceFiles = new HashSet<>();

      sourceFiles.addAll(findFilesToReformat(sourceDirectory, outputDirectory));
      sourceFiles.addAll(findFilesToReformat(testSourceDirectory, testOutputDirectory));

      Set<File> sourceFilesToProcess = filterModified ? filterUnchangedFiles(sourceFiles) : sourceFiles;

      if (!sourceFilesToProcess.isEmpty()) {
        String[] args = getCliArgs(sourceFilesToProcess);
        SystemExitInterceptor.invoke(() -> Main.main(args)); 
      }
    } catch (Exception e) {
      throw new MojoExecutionException(e.getMessage());
    }
  }

  private Set<File> filterUnchangedFiles(Set<File> originalFiles) throws MojoExecutionException {
    try {
      String connectionUrl = MoreObjects.firstNonNull(project.getScm().getConnection(), project.getScm().getDeveloperConnection());
      ScmRepository repository = scmManager.makeScmRepository(connectionUrl);
      ScmFileSet scmFileSet = new ScmFileSet(project.getBasedir());
      final List<String> changedFiles =
          scmManager
              .status(repository, scmFileSet)
              .getChangedFiles()
              .stream()
              .map(f -> String.format("%s/%s", project.getBasedir().getAbsoluteFile().getPath(), f.getPath()))
              .collect(Collectors.toList());

      return originalFiles.stream().filter(f -> changedFiles.contains(f.getPath())).collect(Collectors.toSet());

    } catch (ScmException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }

  private Set<File> findFilesToReformat(File sourceDirectory, File outputDirectory) throws MojoExecutionException {
    if (sourceDirectory.exists()) {
      try {
        SourceInclusionScanner scanner = getSourceInclusionScanner(includeStale);
        scanner.addSourceMapping(new SuffixMapping(".java", new HashSet(Arrays.asList(".java", ".class"))));
        Set<File> sourceFiles = scanner.getIncludedSources(sourceDirectory, outputDirectory);
        getLog().info("Found " + sourceFiles.size() + " uncompiled/modified files in " + sourceDirectory.getPath() + " to reformat.");
        return sourceFiles;
      } catch (InclusionScanException e) {
        throw new MojoExecutionException("Error scanning source path: \'" + sourceDirectory.getPath() + "\' " + "for  files to reformat.", e);
      }
    } else {
      getLog().info(String.format("Directory %s does not exist, skipping file collection.", sourceDirectory.getPath()));
      return Collections.emptySet();
    }
  }

  protected SourceInclusionScanner getSourceInclusionScanner(boolean includeStale) {
    return includeStale ? new SimpleSourceInclusionScanner(Collections.singleton("**/*"), Collections.EMPTY_SET) : new StaleSourceScanner(1024);
  }

  private String[] getCliArgs(Set<File> sourceFilesToProcess) {
    int extraArgs = Style.AOSP.equals(style) ? 2 : 1;
    String[] pathsToProcess =
        sourceFilesToProcess.stream().map(File::getAbsolutePath).toArray(String[]::new);
    final String[] args = new String[extraArgs + pathsToProcess.length];
    args[0] = "--replace";
    if (extraArgs > 1) {
      args[1] = "--aosp";
    }
    System.arraycopy(pathsToProcess, 0, args, extraArgs, pathsToProcess.length);
    return args;
  }
}
