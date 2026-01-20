package org.hoggmania.generators;

import org.hoggmania.BuildSystemSbomGenerator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Generator for creating SBOMs from standalone binaries using Syft.
 * Used when no package manager is detected in the project.
 */
public class BinaryOnlySbomGenerator implements BuildSystemSbomGenerator {

    @Override
    public String getBuildSystemName() {
        return "Standalone Binaries (Syft)";
    }

    @Override
    public String getBuildFilePattern() {
        return "*.jar";
    }

    @Override
    public List<String> getAdditionalBuildFilePatterns() {
        return Arrays.asList(
            "*.war",
            "*.ear",
            "*.zip",
            "*.tar",
            "*.tar.gz",
            "*.tgz",
            "*.tar.bz2",
            "*.tbz2",
            "*.tar.xz",
            "*.txz",
            "*.rpm",
            "*.deb",
            "*.apk",
            "*.nupkg",
            "*.msi",
            "*.exe",
            "*.dll",
            "*.so",
            "*.dylib",
            "*.a",
            "*.lib"
        );
    }

    @Override
    public String getVersionCheckCommand() {
        return "syft version";
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir) {
        return String.format("syft scan dir:. --file-metadata -o cyclonedx-json=%s -q",
            new File(outputDir, projectName + "-bom.json").getAbsolutePath());
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir, Path buildFile) {
        Path scanDir = buildFile;
        if (buildFile == null) {
            scanDir = Path.of(".");
        } else if (!Files.isDirectory(buildFile)) {
            scanDir = buildFile.getParent() != null ? buildFile.getParent() : Path.of(".");
        }
        return String.format("syft scan dir:%s -o cyclonedx-json=%s",
            scanDir.toAbsolutePath(),
            new File(outputDir, projectName + "-bom.json").getAbsolutePath()) + " -q";
    }

    @Override
    public String extractProjectName(Path buildFile) {
        if (buildFile == null) {
            return "binary-scan";
        }
        if (Files.isDirectory(buildFile) && buildFile.getFileName() != null) {
            return buildFile.getFileName().toString();
        }
        Path parent = buildFile.getParent();
        if (parent != null && parent.getFileName() != null) {
            return parent.getFileName().toString();
        }
        return "binary-scan";
    }

    @Override
    public boolean isMultiModule(List<Path> buildFiles) {
        return false;
    }
}
