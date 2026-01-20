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
import java.util.ArrayList;
import java.util.List;

public class PipenvSbomGenerator implements BuildSystemSbomGenerator {
    @Override
    public String getBuildSystemName() {
        return "Pipenv";
    }

    @Override
    public String getBuildFilePattern() {
        return "Pipfile.lock";
    }

    @Override
    public String getVersionCheckCommand() {
        return "python --version";
    }

    @Override
    public List<ToolRequirement> getRequiredTools(Path buildFile) {
        List<ToolRequirement> tools = new ArrayList<>();
        tools.add(new ToolRequirement("python", "python --version", null));
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
        String base = String.format("cyclonedx-py pipenv --dev --sv 1.6 --of JSON -o %s/%s",
            outputDir.getAbsolutePath(), outputFile);
        String extra = (additionalArgs != null && !additionalArgs.isBlank()) ? " " + additionalArgs.trim() : "";
        if (root != null) {
            base = String.format("cd %s && %s%s %s",
                root.toAbsolutePath(), base, extra, root.toAbsolutePath());
        } else {
            base = base + extra;
        }
        return base;
    }

    @Override
    public String extractProjectName(Path buildFile) {
        Path root = buildFile != null ? buildFile.getParent() : null;
        if (root != null && root.getFileName() != null) {
            return root.getFileName().toString();
        }
        return "pipenv-project";
    }
}
