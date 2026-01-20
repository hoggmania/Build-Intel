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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PoetrySbomGenerator implements BuildSystemSbomGenerator {
    private static final Pattern GROUP_PATTERN =
        Pattern.compile("^\\s*\\[tool\\.poetry\\.group\\.([^\\]]+)\\]\\s*$");

    @Override
    public String getBuildSystemName() {
        return "Poetry";
    }

    @Override
    public String getBuildFilePattern() {
        return "poetry.lock";
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
        StringBuilder base = new StringBuilder(String.format(
            "cyclonedx-py poetry --all-extras --sv 1.6 --of JSON -o %s/%s",
            outputDir.getAbsolutePath(), outputFile));

        String extra = (additionalArgs != null && !additionalArgs.isBlank()) ? " " + additionalArgs.trim() : "";
        if (root != null) {
            List<String> groups = readPoetryGroups(root);
            for (String group : groups) {
                base.append(" --with ").append(group);
            }
            base = new StringBuilder(String.format("cd %s && %s%s %s",
                root.toAbsolutePath(), base, extra, root.toAbsolutePath()));
        } else {
            base.append(extra);
        }
        return base.toString();
    }

    @Override
    public String extractProjectName(Path buildFile) {
        Path root = buildFile != null ? buildFile.getParent() : null;
        if (root != null && root.getFileName() != null) {
            return root.getFileName().toString();
        }
        return "poetry-project";
    }

    private List<String> readPoetryGroups(Path root) {
        Path pyproject = root.resolve("pyproject.toml");
        if (!Files.exists(pyproject)) {
            return List.of();
        }
        List<String> groups = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(pyproject);
            for (String line : lines) {
                Matcher matcher = GROUP_PATTERN.matcher(line);
                if (matcher.find()) {
                    String group = matcher.group(1).trim();
                    if (!group.isEmpty()) {
                        groups.add(group);
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return groups;
    }
}
