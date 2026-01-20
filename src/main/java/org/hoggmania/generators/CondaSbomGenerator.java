/*
 * MIT License
 *
 * Copyright (c) 2025 James Holland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.hoggmania.generators;

import org.hoggmania.BuildSystemSbomGenerator;
import org.hoggmania.ToolRequirement;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class CondaSbomGenerator implements BuildSystemSbomGenerator {
    @Override
    public String getBuildSystemName() {
        return "Conda";
    }

    @Override
    public String getBuildFilePattern() {
        return "environment.yml";
    }

    @Override
    public List<String> getAdditionalBuildFilePatterns() {
        return List.of("environment.yaml", "conda.yml", "conda.yaml");
    }

    @Override
    public String getVersionCheckCommand() {
        return "syft version";
    }

    @Override
    public List<ToolRequirement> getRequiredTools(Path buildFile) {
        return Collections.singletonList(new ToolRequirement("syft", "syft version", null));
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir) {
        return generateSbomCommand(projectName, outputDir, null, "");
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir, Path buildFile) {
        return generateSbomCommand(projectName, outputDir, buildFile, "");
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir, Path buildFile, String additionalArgs) {
        String outputFile = projectName + "-bom.json";
        Path root = buildFile != null ? buildFile.getParent() : Path.of(".");
        String base = String.format("syft scan dir:%s -o cyclonedx-json=%s",
            root.toAbsolutePath(), new File(outputDir, outputFile).getAbsolutePath());
        if (additionalArgs != null && !additionalArgs.isBlank()) {
            base = base + " " + additionalArgs.trim();
        }
        return base;
    }

    @Override
    public String extractProjectName(Path buildFile) {
        Path root = buildFile != null ? buildFile.getParent() : null;
        if (root != null && root.getFileName() != null) {
            return root.getFileName().toString();
        }
        return "conda-project";
    }
}
