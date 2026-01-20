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

public class RustSbomGenerator implements BuildSystemSbomGenerator {
    @Override
    public String getBuildSystemName() {
        return "Rust";
    }

    @Override
    public String getBuildFilePattern() {
        return "Cargo.toml";
    }

    @Override
    public String getVersionCheckCommand() {
        return "cargo --version";
    }

    @Override
    public List<ToolRequirement> getRequiredTools(Path buildFile) {
        List<ToolRequirement> tools = new ArrayList<>();
        tools.add(new ToolRequirement("cargo", "cargo --version", null));
        tools.add(new ToolRequirement("cargo-cyclonedx", "cargo cyclonedx --version", "cargo install cargo-cyclonedx"));
        return tools;
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir) {
        String outputFile = projectName + "-bom.json";
        String overrideName = projectName + "-bom";
        String base = String.format("cargo cyclonedx -f json --override-filename %s", overrideName);
        return appendMoveCommand(base, Path.of(outputFile), new File(outputDir, outputFile));
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir, Path buildFile) {
        String outputFile = projectName + "-bom.json";
        String overrideName = projectName + "-bom";
        Path root = buildFile != null ? buildFile.getParent() : null;
        String manifestArg = buildFile != null ? " --manifest-path " + buildFile.toAbsolutePath() : "";
        String base = String.format("cargo cyclonedx -f json%s --override-filename %s", manifestArg, overrideName);
        Path source = root != null ? root.resolve(outputFile) : Path.of(outputFile);
        return appendMoveCommand(base, source, new File(outputDir, outputFile));
    }

    private String appendMoveCommand(String baseCommand, Path source, File destination) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String moveCommand;
        if (isWindows) {
            moveCommand = String.format("move /Y \"%s\" \"%s\"", source.toAbsolutePath(), destination.getAbsolutePath());
        } else {
            moveCommand = String.format("mv -f \"%s\" \"%s\"", source.toAbsolutePath(), destination.getAbsolutePath());
        }
        return baseCommand + " && " + moveCommand;
    }

    @Override
    public String extractProjectName(Path cargoToml) {
        try {
            String content = Files.readString(cargoToml);
            int packageStart = content.indexOf("[package]");
            if (packageStart >= 0) {
                int nameStart = content.indexOf("name =", packageStart);
                if (nameStart > 0) {
                    int quoteStart = content.indexOf("\"", nameStart);
                    if (quoteStart > 0) {
                        int quoteEnd = content.indexOf("\"", quoteStart + 1);
                        if (quoteEnd > 0) {
                            return content.substring(quoteStart + 1, quoteEnd).trim();
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Ignore
        }
        return "rust-project";
    }
}
