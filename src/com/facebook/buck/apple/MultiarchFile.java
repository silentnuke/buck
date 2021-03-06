/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Puts together multiple thin library/binaries into a multi-arch file. */
public class MultiarchFile extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements ProvidesLinkedBinaryDeps {

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final Tool lipo;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> thinBinaries;

  @AddToRuleKey(stringify = true)
  private final Path output;

  public MultiarchFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      Tool lipo,
      ImmutableSortedSet<SourcePath> thinBinaries,
      Path output) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.ruleFinder = ruleFinder;
    this.lipo = lipo;
    this.thinBinaries = ImmutableSortedSet.copyOf(thinBinaries);
    this.output = output;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    lipoBinaries(context, steps);
    copyLinkMaps(buildableContext, context, steps);

    return steps.build();
  }

  private void copyLinkMaps(
      BuildableContext buildableContext,
      BuildContext buildContext,
      ImmutableList.Builder<Step> steps) {
    Path linkMapDir = Paths.get(output + "-LinkMap");
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), linkMapDir)));

    for (SourcePath thinBinary : thinBinaries) {
      Optional<BuildRule> maybeRule = ruleFinder.getRule(thinBinary);
      if (maybeRule.isPresent()) {
        BuildRule rule = maybeRule.get();
        if (rule instanceof CxxBinary) {
          rule = ((CxxBinary) rule).getLinkRule();
        }
        if (rule instanceof CxxLink
            && !rule.getBuildTarget()
                .getFlavors()
                .contains(LinkerMapMode.NO_LINKER_MAP.getFlavor())) {
          Optional<Path> maybeLinkerMapPath = ((CxxLink) rule).getLinkerMapPath();
          if (maybeLinkerMapPath.isPresent()) {
            Path source = maybeLinkerMapPath.get();
            Path dest = linkMapDir.resolve(source.getFileName());
            steps.add(CopyStep.forFile(getProjectFilesystem(), source, dest));
            buildableContext.recordArtifact(dest);
          }
        }
      }
    }
  }

  private void lipoBinaries(BuildContext context, ImmutableList.Builder<Step> steps) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(lipo.getCommandPrefix(context.getSourcePathResolver()));
    commandBuilder.add("-create", "-output", getProjectFilesystem().resolve(output).toString());
    for (SourcePath thinBinary : thinBinaries) {
      commandBuilder.add(context.getSourcePathResolver().getAbsolutePath(thinBinary).toString());
    }
    steps.add(
        new DefaultShellStep(
            getProjectFilesystem().getRootPath(),
            commandBuilder.build(),
            lipo.getEnvironment(context.getSourcePathResolver())));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  @Override
  public ImmutableSet<BuildRule> getStaticLibraryDeps() {
    ImmutableSet.Builder<BuildRule> builder = ImmutableSet.builder();
    for (BuildRule dep : getBuildDeps()) {
      if (dep instanceof ProvidesLinkedBinaryDeps) {
        builder.addAll(((ProvidesLinkedBinaryDeps) dep).getStaticLibraryDeps());
      }
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<BuildRule> getCompileDeps() {
    ImmutableSet.Builder<BuildRule> builder = ImmutableSet.builder();
    for (BuildRule dep : getBuildDeps()) {
      if (dep instanceof ProvidesLinkedBinaryDeps) {
        builder.addAll(((ProvidesLinkedBinaryDeps) dep).getCompileDeps());
      }
    }
    return builder.build();
  }
}
