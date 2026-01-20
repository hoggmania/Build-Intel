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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class UvSbomGenerator implements BuildSystemSbomGenerator {
    @Override
    public String getBuildSystemName() {
        return "uv";
    }

    @Override
    public String getBuildFilePattern() {
        return "uv.lock";
    }

    @Override
    public String getVersionCheckCommand() {
        return "uv --version";
    }

    @Override
    public List<ToolRequirement> getRequiredTools(Path buildFile) {
        List<ToolRequirement> tools = new ArrayList<>();
        tools.add(new ToolRequirement("uv", "uv --version", null));
        tools.add(new ToolRequirement("cyclonedx-py", "cyclonedx-py --version", "python -m pip install cyclonedx-bom"));
        return tools;
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
        Path root = buildFile != null ? buildFile.getParent() : null;
        Path pyproject = root != null ? root.resolve("pyproject.toml") : null;
        String pyprojectArg = (pyproject != null && Files.exists(pyproject))
            ? " --pyproject " + pyproject.toAbsolutePath()
            : "";

        String base = String.format("uv run cyclonedx-py environment --sv 1.6 --of JSON -o %s/%s%s",
            outputDir.getAbsolutePath(), outputFile, pyprojectArg);

        if (root != null) {
            base = String.format("cd %s && %s", root.toAbsolutePath(), base);
        }
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
        return "uv-project";
    }
}
