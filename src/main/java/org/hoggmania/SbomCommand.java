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
package org.hoggmania;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Command to generate SBOM (Software Bill of Materials) using appropriate CycloneDX plugin.
 *
 * <p>This command leverages {@link EnvScannerCommand} for environment detection and build system
 * discovery, eliminating code duplication and ensuring consistent detection logic across both
 * scan and sbom commands.</p>
 *
 * <p>Architecture:
 * <ul>
 *   <li>Uses {@link EnvScannerCommand#scanFiles(Path)} to detect build systems</li>
 *   <li>Uses {@link EnvScannerCommand#detectMultiModuleBuilds(Map)} to determine multi-module status</li>
 *   <li>Delegates SBOM generation to build-system-specific generators in org.hoggmania.generators</li>
 * </ul>
 * </p>
 */
@Command(name = "sbom", mixinStandardHelpOptions = true, description = "Generate SBOM using appropriate CycloneDX plugin for detected build system")
public class SbomCommand implements Runnable {
    private static final Pattern ADDITIONAL_ARGS_ALLOWED =
        Pattern.compile("^[A-Za-z0-9_\\-./=:+,@\\\\\\s]*$");

    @Parameters(index = "0", description = "Root directory to inspect.", defaultValue = "./")
    File rootDir;

    @Option(names = {"-o", "--output"}, description = "Output directory for SBOM file", defaultValue = "generated-sboms")
    File outputDir;

    @Option(names = "--dry-run", description = "Show which plugin would be used without executing")
    boolean dryRun;

    @Option(names = {"-m", "--merge"}, description = "Merge all generated SBOMs into a single file")
    boolean merge;

    @Option(names = {"-j", "--json"}, description = "Output summary results to JSON file")
    boolean jsonOutput;

    @Option(names = {"-a","--additional-args"}, description = "Additional arguments to pass to the underlying build tool (e.g. Maven, npm, etc.)")
    String additionalArgs = "";

    @Option(names = "--allow-tool-install", description = "Allow installing missing SBOM tools when needed")
    boolean allowToolInstall;

    @Option(names = {"-r", "--root"}, description = "Working directory for command execution (default: root directory)")
    File rootWorkingDir;

    @Option(names = {"-s", "--sbom-only"}, description = "Generate SBOM directly using Syft without build system detection")
    boolean sbomOnly;

    private final Map<Path, String> syftOutputsByDir = new HashMap<>();

    @RegisterForReflection
    static class BuildSystemInfo {
        String buildSystem;
        String pluginCommand;
        String version;
        boolean multiModule;
        List<String> buildFiles;
        String projectName;
        Path workingDirectory;

        public BuildSystemInfo(String buildSystem, String pluginCommand) {
            this.buildSystem = buildSystem;
            this.pluginCommand = pluginCommand;
            this.buildFiles = new ArrayList<>();
            this.projectName = "project";
        }
    }

    @RegisterForReflection
    static class SbomGenerationResult {
        BuildSystemInfo buildInfo;
        boolean success;
        String output;
        String errorOutput;
        String expectedSbomPath;
        boolean sbomFileExists;
        long sbomFileSize;
        String timestamp;
        int exitCode;
        String errorMessage;

        public SbomGenerationResult(BuildSystemInfo buildInfo) {
            this.buildInfo = buildInfo;
            this.timestamp = java.time.Instant.now().toString();
        }
    }



    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[] {".", "--dry-run"};
        }

        System.exit(new CommandLine(new SbomCommand()).execute(args));
    }


    @Override
    public void run() {
        try {
            additionalArgs = validateAdditionalArgs(additionalArgs);

            // Create output directory if it doesn't exist
            if (!outputDir.exists()) {
                outputDir.mkdirs();
                System.out.println("Created output directory: " + outputDir.getAbsolutePath());
            }

            // Handle SBOM-only mode - use Syft directly without build system detection
            if (sbomOnly) {
                generateSbomOnlyWithSyft();
                return;
            }

            System.out.println("Inspecting environment at: " + rootDir.getAbsolutePath());
            System.out.println("Output directory: " + outputDir.getAbsolutePath());

            // Use EnvScannerCommand to scan for build systems
            EnvScannerCommand scanner = new EnvScannerCommand();
            Map<String, List<Path>> foundFiles = scanner.scanFiles(rootDir.toPath());

            // Use detailed build system detection to get filtered instances (excludes child modules)
            Map<String, List<EnvScannerCommand.BuildSystemInstance>> detailedBuildSystems =
                scanner.detectDetailedBuildSystems(foundFiles);

            List<BuildSystemInfo> buildSystems = convertToBuildSystemInfo(detailedBuildSystems);

            // If no build systems found, check for standalone binaries
            if (buildSystems.isEmpty()) {
                List<BuildSystemInfo> jarSystems = detectStandaloneJars(rootDir.toPath(), outputDir);
                if (!jarSystems.isEmpty()) {
                    buildSystems.addAll(jarSystems);
                }
            }

            if (buildSystems.isEmpty()) {
                if (generateEvidenceOnlySbom(foundFiles)) {
                    return;
                }
                System.err.println(ConsoleColors.error("[ERROR]") + " No supported build system detected");
                System.err.println("Supported build systems:");
                System.err.println("  - Maven (pom.xml)");
                System.err.println("  - Gradle (build.gradle, build.gradle.kts)");
                System.err.println("  - npm (package.json)");
                System.err.println("  - Yarn (yarn.lock)");
                System.err.println("  - pnpm (pnpm-lock.yaml)");
                System.err.println("  - Pipenv (Pipfile.lock)");
                System.err.println("  - Poetry (poetry.lock)");
                System.err.println("  - uv (uv.lock)");
                System.err.println("  - Conda (environment.yml)");
                System.err.println("  - Python (setup.py, pyproject.toml, requirements.txt)");
                System.err.println("  - Go (go.mod)");
                System.err.println("  - .NET (*.csproj, *.vbproj, *.fsproj, *.sln)");
                System.err.println("  - Rust (Cargo.toml)");
                System.err.println("  - PHP (composer.json)");
                System.err.println("  - Ruby (Gemfile)");
                System.err.println("  - Standalone Binaries (scanned with Syft)");
                return;
            }

            System.out.println(ConsoleColors.bold("\n=== Build Systems Detected: " + buildSystems.size() + " ==="));
            for (BuildSystemInfo buildInfo : buildSystems) {
                System.out.println("\n--- " + ConsoleColors.info(buildInfo.buildSystem) + " ---");
                System.out.println("Project Name: " + ConsoleColors.highlight(buildInfo.projectName));
                System.out.println("Multi-Module: " + buildInfo.multiModule);
                System.out.println("Build Files Found: " + buildInfo.buildFiles.size());
                buildInfo.buildFiles.forEach(f -> System.out.println("  - " + f));
                System.out.println("Working Directory: " + (buildInfo.workingDirectory != null ? buildInfo.workingDirectory : rootDir.toPath()));
                System.out.println("Command: " + buildInfo.pluginCommand);
            }

            if (dryRun) {
                System.out.println(ConsoleColors.bold("\n[DRY-RUN]") + " Would execute the following commands:");
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                for (BuildSystemInfo buildInfo : buildSystems) {
                    System.out.println("\n--- " + ConsoleColors.info(buildInfo.buildSystem) + " ---");
                    System.out.println("Project Name: " + ConsoleColors.highlight(buildInfo.projectName));
                    File workDir = buildInfo.workingDirectory != null ? buildInfo.workingDirectory.toFile() : rootDir;
                    System.out.println("Working Directory: " + workDir.getAbsolutePath());
                    System.out.println("\nBuild Files:");
                    buildInfo.buildFiles.forEach(f -> System.out.println("  - " + f));
                    System.out.println("\nCommand to execute:");
                    if (isWindows) {
                    System.out.println("  cmd.exe /c " + buildInfo.pluginCommand);
                } else {
                    System.out.println("  sh -c " + buildInfo.pluginCommand);
                }
            }
            if (jsonOutput) {
                writeSummaryJson(buildSystems, true, new ArrayList<>());
            }
            return;
        }            System.out.println(ConsoleColors.bold("\n=== Generating SBOMs ==="));
            List<Boolean> results = new ArrayList<>();
            List<SbomGenerationResult> generationResults = new ArrayList<>();
            for (BuildSystemInfo buildInfo : buildSystems) {
                System.out.println("\n--- Generating SBOM for " + ConsoleColors.info(buildInfo.buildSystem) + " ---");
                SbomGenerationResult result = generateSbomWithDetails(buildInfo);
                results.add(result.success);
                generationResults.add(result);
            }

            if (merge && buildSystems.size() > 1) {
                System.out.println(ConsoleColors.bold("\n=== Merging SBOMs ==="));
                mergeSboms();
            }

            // Write summary JSON only if --json flag is specified
            if (jsonOutput) {
                writeSummaryJson(buildSystems, false, results);

                // Write aggregate log
                writeAggregateLog(generationResults);
            }

        } catch (IOException e) {
            throw new RuntimeException("Error inspecting environment", e);
        }
    }

    private static String validateAdditionalArgs(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        String trimmed = args.trim();
        if (!ADDITIONAL_ARGS_ALLOWED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                "Additional args contain unsupported characters. " +
                "Allowed: letters, digits, space, and - _ . / = : + , @ \\");
        }
        return trimmed;
    }

    /**
     * Detect standalone binaries when no package manager is present.
     * Returns a list of BuildSystemInfo entries for binary branches.
     */
    private List<BuildSystemInfo> detectStandaloneJars(Path rootDir, File outputDir) throws IOException {
        org.hoggmania.generators.BinaryOnlySbomGenerator binaryGenerator =
            new org.hoggmania.generators.BinaryOnlySbomGenerator();

        List<String> patterns = new ArrayList<>();
        patterns.add(binaryGenerator.getBuildFilePattern());
        patterns.addAll(binaryGenerator.getAdditionalBuildFilePatterns());

        Set<Path> binaryFiles = new LinkedHashSet<>();
        for (String pattern : patterns) {
            binaryFiles.addAll(EnvScannerCommand.findFilesByPattern(rootDir, pattern));
        }

        if (binaryFiles.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Path, List<Path>> branches = groupJarFilesByBranch(rootDir, new ArrayList<>(binaryFiles));
        List<BuildSystemInfo> results = new ArrayList<>();
        for (Map.Entry<Path, List<Path>> entry : branches.entrySet()) {
            Path branchRoot = entry.getKey();
            String projectName = binaryGenerator.extractProjectName(branchRoot);
            String sbomCommand = binaryGenerator.generateSbomCommand(projectName, outputDir, branchRoot, additionalArgs);

            BuildSystemInfo info = new BuildSystemInfo("Standalone Binaries", sbomCommand);
            info.buildFiles = entry.getValue().stream().map(Path::toString).collect(Collectors.toList());
            info.multiModule = false;
            info.projectName = projectName;
            info.workingDirectory = rootWorkingDir != null ? rootWorkingDir.toPath() : branchRoot;

            results.add(info);
        }

        return results;
    }

    private Map<Path, List<Path>> groupJarFilesByBranch(Path rootDir, List<Path> jarFiles) {
        Path root = rootDir.toAbsolutePath().normalize();
        Map<Path, List<Path>> initial = new LinkedHashMap<>();

        for (Path jarFile : jarFiles) {
            Path absJar = jarFile.toAbsolutePath().normalize();
            Path branch = resolveJarBranch(root, absJar);
            initial.computeIfAbsent(branch, k -> new ArrayList<>()).add(absJar);
        }

        List<Path> candidates = new ArrayList<>(initial.keySet());
        candidates.sort(Comparator.comparingInt(Path::getNameCount));

        List<Path> roots = new ArrayList<>();
        for (Path candidate : candidates) {
            if (!hasAncestor(roots, candidate)) {
                roots.add(candidate);
            }
        }

        Map<Path, List<Path>> grouped = new LinkedHashMap<>();
        for (Path rootBranch : roots) {
            grouped.put(rootBranch, new ArrayList<>());
        }

        for (Map.Entry<Path, List<Path>> entry : initial.entrySet()) {
            Path candidate = entry.getKey();
            Path rootBranch = findRootBranch(candidate, roots);
            grouped.get(rootBranch).addAll(entry.getValue());
        }

        return grouped;
    }

    private Path resolveJarBranch(Path root, Path jarFile) {
        Path current = jarFile.getParent();
        while (current != null && current.getFileName() != null) {
            String name = current.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!FilePatterns.EXCLUDED_DIRECTORIES.contains(name)) {
                break;
            }
            current = current.getParent();
        }
        if (current == null || !current.startsWith(root)) {
            return root;
        }
        return current;
    }

    private boolean hasAncestor(List<Path> roots, Path candidate) {
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private Path findRootBranch(Path candidate, List<Path> roots) {
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return root;
            }
        }
        return candidate;
    }

    /**
     * Convert the detailed build system instances from EnvScannerCommand into BuildSystemInfo objects
     * that contain the necessary information for SBOM generation.
     *
     * Uses BuildSystemGeneratorRegistry to abstract build system-specific logic.
     * Only processes build systems that are not filtered out (i.e., multi-module roots and standalone projects).
     */
    private List<BuildSystemInfo> convertToBuildSystemInfo(
            Map<String, List<EnvScannerCommand.BuildSystemInstance>> detailedBuildSystems) throws IOException {
        List<BuildSystemInfo> buildSystems = new ArrayList<>();

        for (Map.Entry<String, List<EnvScannerCommand.BuildSystemInstance>> entry : detailedBuildSystems.entrySet()) {
            String buildSystemName = entry.getKey();
            List<EnvScannerCommand.BuildSystemInstance> instances = entry.getValue();

            // Process each instance (these are already filtered - child modules excluded)
            for (EnvScannerCommand.BuildSystemInstance instance : instances) {
                // Get the generator for this build system
                BuildSystemGeneratorRegistry.getGenerator(buildSystemName).ifPresent(generator -> {
                    Path buildFilePath = Paths.get(instance.getPath());

                    // Extract project name using generator
                    String projectName = generator.extractProjectName(buildFilePath);

                    // Generate SBOM command using generator with absolute build file path and additionalArgs
                    String sbomCommand = generator.generateSbomCommand(projectName, outputDir, buildFilePath, additionalArgs);

                    // Create BuildSystemInfo
                    BuildSystemInfo info = new BuildSystemInfo(buildSystemName, sbomCommand);
                    info.buildFiles = Collections.singletonList(instance.getPath());
                    info.multiModule = instance.isMultiModule();
                    info.projectName = projectName;

                    // Set working directory to build file's parent directory
                    // (where settings.gradle/pom.xml/package.json etc. are located)
                    // Override with user-provided rootWorkingDir if specified
                    info.workingDirectory = rootWorkingDir != null ? rootWorkingDir.toPath() : buildFilePath.getParent();

                    buildSystems.add(info);
                });
            }
        }

        return buildSystems;
    }

    /**
     * Generate SBOM directly with Syft without build system detection.
     * This mode scans the root directory and generates an SBOM using Syft.
     */
    private void generateSbomOnlyWithSyft() {
        System.out.println(ConsoleColors.info("[SBOM-ONLY MODE]") + " Generating SBOM with Syft for: " + rootDir.getAbsolutePath());

        // Check if Syft is installed
        System.out.println("Checking if Syft is installed...");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        try {
            ProcessBuilder versionCheck = new ProcessBuilder();
            if (isWindows) {
                versionCheck.command("cmd.exe", "/c", "syft version");
            } else {
                versionCheck.command("sh", "-c", "syft version");
            }

            Process versionProcess = versionCheck.start();
            int versionExitCode = versionProcess.waitFor();

            if (versionExitCode != 0) {
                System.err.println(ConsoleColors.error("[ERROR]") + " Syft is not installed or not in PATH.");
                System.err.println("Please install Syft: https://github.com/anchore/syft");
                return;
            }

            System.out.println(ConsoleColors.success("[OK]") + " Syft is available");

        } catch (Exception e) {
            System.err.println(ConsoleColors.error("[ERROR]") + " Failed to check for Syft: " + e.getMessage());
            System.err.println("Please install Syft: https://github.com/anchore/syft");
            return;
        }

        // Determine project name from directory
        String projectName = rootDir.getName();
        if (projectName == null || projectName.isEmpty()) {
            projectName = "project";
        }

        // Construct Syft command
        String outputFileName = projectName + "-bom.json";
        File sbomFile = new File(outputDir, outputFileName);

        String syftCommand = String.format("syft scan dir:%s -o cyclonedx-json=%s",
            rootDir.getAbsolutePath(),
            sbomFile.getAbsolutePath());

        System.out.println("\nRunning Syft command:");
        System.out.println("  " + syftCommand);

        try {
            List<String> cmd = new ArrayList<>();
            if (isWindows) {
                cmd.add("cmd.exe");
                cmd.add("/c");
            } else {
                cmd.add("sh");
                cmd.add("-c");
            }
            cmd.add(syftCommand);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(rootDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (line.trim().length() > 0) {
                        System.out.println("  " + line);
                    }
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("\n" + ConsoleColors.success("[SUCCESS]") + " SBOM generated successfully");

                // Check if file exists and get size
                if (sbomFile.exists()) {
                    long fileSize = sbomFile.length();
                    System.out.println("SBOM file: " + ConsoleColors.highlight(sbomFile.getAbsolutePath()));
                    System.out.println("File size: " + fileSize + " bytes");

                    // Write summary only if --json flag is specified
                    if (jsonOutput) {
                        writeSbomOnlySummary(projectName, sbomFile, fileSize, true, null);
                    }
                } else {
                    System.err.println(ConsoleColors.warning("[WARNING]") + " SBOM file not found at expected location: " + sbomFile.getAbsolutePath());
                    if (jsonOutput) {
                        writeSbomOnlySummary(projectName, sbomFile, 0, false, "SBOM file not found at expected location");
                    }
                }
            } else {
                System.err.println("\n" + ConsoleColors.error("[ERROR]") + " Syft command failed with exit code: " + exitCode);
                System.err.println("Output:\n" + output.toString());
                if (jsonOutput) {
                    writeSbomOnlySummary(projectName, sbomFile, 0, false, "Syft command failed with exit code: " + exitCode);
                }
            }

        } catch (Exception e) {
            System.err.println(ConsoleColors.error("[ERROR]") + " Failed to execute Syft: " + e.getMessage());
            e.printStackTrace();
            if (jsonOutput) {
                writeSbomOnlySummary(projectName, sbomFile, 0, false, "Exception: " + e.getMessage());
            }
        }
    }

    /**
     * Write summary JSON for SBOM-only mode.
     */
    private void writeSbomOnlySummary(String projectName, File sbomFile, long fileSize, boolean success, String errorMessage) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("timestamp", new Date().toString());
            summary.put("mode", "sbom-only");
            summary.put("tool", "Syft");
            summary.put("rootDirectory", rootDir.getAbsolutePath());
            summary.put("outputDirectory", outputDir.getAbsolutePath());
            summary.put("projectName", projectName);
            summary.put("sbomFile", sbomFile.getAbsolutePath());
            summary.put("success", success);

            if (success) {
                summary.put("sbomFileSize", fileSize);
            }

            if (errorMessage != null) {
                summary.put("errorMessage", errorMessage);
            }

            ObjectMapper mapper = new ObjectMapper();
            File summaryFile = new File(outputDir, "sbom-summary.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(summaryFile, summary);
            System.out.println("\n" + ConsoleColors.success("[SUCCESS]") + " Summary written to " +
                ConsoleColors.highlight(summaryFile.getAbsolutePath()));

        } catch (IOException e) {
            System.err.println(ConsoleColors.warning("[WARNING]") + " Failed to write summary file: " + e.getMessage());
        }
    }

    /**
     * Generate SBOM for standalone binaries using Syft.
     */
    private SbomGenerationResult generateBinarySbom(BuildSystemInfo buildInfo) {
        SbomGenerationResult result = new SbomGenerationResult(buildInfo);

        SbomGenerationResult cached = reuseSyftOutputIfAvailable(buildInfo);
        if (cached != null) {
            return cached;
        }

        try {
            org.hoggmania.generators.BinaryOnlySbomGenerator binaryGenerator =
                new org.hoggmania.generators.BinaryOnlySbomGenerator();

            // Check if Syft is available
            System.out.println("Checking if Syft is installed...");
            ProcessBuilder versionCheck = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                versionCheck.command("cmd.exe", "/c", binaryGenerator.getVersionCheckCommand());
            } else {
                versionCheck.command("sh", "-c", binaryGenerator.getVersionCheckCommand());
            }

            try {
                Process versionProcess = versionCheck.start();
                int versionExitCode = versionProcess.waitFor();
                if (versionExitCode != 0) {
                    result.success = false;
                    result.errorMessage = "Syft is not installed or not in PATH. Please install Syft: https://github.com/anchore/syft";
                    System.err.println(ConsoleColors.error("[ERROR]") + " " + result.errorMessage);
                    if (jsonOutput) {
                        writeSbomLogFile(result);
                    }
                    return result;
                }
            } catch (Exception e) {
                result.success = false;
                result.errorMessage = "Syft is not installed or not in PATH. Please install Syft: https://github.com/anchore/syft";
                System.err.println(ConsoleColors.error("[ERROR]") + " " + result.errorMessage);
                if (jsonOutput) {
                    writeSbomLogFile(result);
                }
                return result;
            }

            // Generate SBOM using Syft
            Path primaryBuildFile = buildInfo.buildFiles != null && !buildInfo.buildFiles.isEmpty() ? Paths.get(buildInfo.buildFiles.get(0)) : null;
            String sbomCommand = binaryGenerator.generateSbomCommand(buildInfo.projectName, outputDir, primaryBuildFile);
            System.out.println("Generating SBOM with Syft...");
            System.out.println("Command: " + sbomCommand);

            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd.exe", "/c", sbomCommand);
            } else {
                pb.command("sh", "-c", sbomCommand);
            }
            pb.directory(buildInfo.workingDirectory.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            result.output = output;
            result.exitCode = exitCode;

            if (exitCode == 0) {
                File outputFile = new File(outputDir, buildInfo.projectName + "-bom.json");
                System.out.println("\n" + ConsoleColors.success("[SUCCESS]") + " SBOM generated: " + outputFile.getAbsolutePath());

                result.success = true;
                result.expectedSbomPath = outputFile.getAbsolutePath();
                result.sbomFileExists = outputFile.exists();
                if (outputFile.exists()) {
                    result.sbomFileSize = outputFile.length();
                }
                cacheSyftOutput(buildInfo, result.expectedSbomPath);
            } else {
                result.success = false;
                result.errorMessage = "Syft failed with exit code " + exitCode;
                result.errorOutput = output;
                System.err.println(ConsoleColors.error("[ERROR]") + " " + result.errorMessage);
                System.err.println(output);
            }

            if (jsonOutput) {
                writeSbomLogFile(result);
            }

        } catch (Exception e) {
            result.success = false;
            result.errorMessage = "Failed to generate SBOM with Syft: " + e.getMessage();
            result.errorOutput = e.toString();
            System.err.println(ConsoleColors.error("[ERROR]") + " " + result.errorMessage);
            e.printStackTrace();
            if (jsonOutput) {
                writeSbomLogFile(result);
            }
        }

        return result;
    }

    private SbomGenerationResult generateSbomWithDetails(BuildSystemInfo buildInfo) {
        SbomGenerationResult result = new SbomGenerationResult(buildInfo);

        // Special handling for standalone binaries - generate SBOM with Syft
        if ("Standalone Binaries".equals(buildInfo.buildSystem)) {
            return generateBinarySbom(buildInfo);
        }

        SbomGenerationResult cached = reuseSyftOutputIfAvailable(buildInfo);
        if (cached != null) {
            return cached;
        }

        // First check if the required tools are available
        String toolError = ensureRequiredTools(buildInfo);
        if (toolError != null) {
            result.success = false;
            result.errorMessage = toolError;
            System.err.println(ConsoleColors.error("[ERROR]") + " " + result.errorMessage);
            if (jsonOutput) {
                writeSbomLogFile(result);
            }
            return result;
        }

        // For Go projects, ensure dependencies are downloaded first
        if ("Go".equals(buildInfo.buildSystem)) {
            if (!prepareGoModule(buildInfo)) {
                System.err.println(ConsoleColors.warning("[WARNING]") + " Failed to prepare Go module, continuing anyway...");
            }
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File workDir = rootWorkingDir != null ? rootWorkingDir :
                      (buildInfo.workingDirectory != null ? buildInfo.workingDirectory.toFile() : rootDir);
        StringBuilder outputCapture = new StringBuilder();
        StringBuilder errorCapture = new StringBuilder();

        try {
            List<String> command = new ArrayList<>();

            if (isWindows) {
                command.add("cmd.exe");
                command.add("/c");
            } else {
                command.add("sh");
                command.add("-c");
            }

            command.add(buildInfo.pluginCommand);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(false);

            System.out.println("Executing: " + buildInfo.pluginCommand);
            System.out.println("Working directory: " + workDir.getAbsolutePath());
            System.out.println();

            Process process = pb.start();

            // Capture stdout and stderr
            Thread outputThread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                        outputCapture.append(line).append("\n");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            Thread errorThread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println(line);
                        errorCapture.append(line).append("\n");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            outputThread.start();
            errorThread.start();

            int exitCode = process.waitFor();
            outputThread.join();
            errorThread.join();

            result.exitCode = exitCode;
            result.output = outputCapture.toString();
            result.errorOutput = errorCapture.toString();
            result.expectedSbomPath = getExpectedSbomPath(buildInfo);

            File sbomFile = new File(result.expectedSbomPath);
            result.sbomFileExists = sbomFile.exists();
            if (sbomFile.exists()) {
                result.sbomFileSize = sbomFile.length();
            }

            if (exitCode == 0) {
                result.success = true;
                System.out.println("\n" + ConsoleColors.success("[SUCCESS]") + " SBOM generated successfully");
                System.out.println("Output location: " + ConsoleColors.highlight(outputDir.getAbsolutePath()));
                cacheSyftOutput(buildInfo, result.expectedSbomPath);
            } else {
                result.success = false;
                result.errorMessage = "SBOM generation failed with exit code: " + exitCode;
                System.err.println("\n" + ConsoleColors.error("[ERROR]") + " " + result.errorMessage);
            }

            if (jsonOutput) {
                writeSbomLogFile(result);
            }
            return result;

        } catch (IOException | InterruptedException e) {
            result.success = false;
            result.errorMessage = "Failed to execute SBOM generation: " + e.getMessage();
            result.output = outputCapture.toString();
            result.errorOutput = errorCapture.toString();
            System.err.println(ConsoleColors.error("[ERROR]") + " " + result.errorMessage);
            e.printStackTrace();
            if (jsonOutput) {
                writeSbomLogFile(result);
            }
            return result;
        }
    }

    private String getExpectedSbomPath(BuildSystemInfo buildInfo) {
        String sbomFileName = buildInfo.projectName + "-bom.json";
        return new File(outputDir, sbomFileName).getAbsolutePath();
    }

    private void writeSbomLogFile(SbomGenerationResult result) {
        try {
            BuildSystemInfo buildInfo = result.buildInfo;
            String logFileName = buildInfo.projectName + "-" + buildInfo.buildSystem.toLowerCase().replace(" ", "-") + "-sbom-log.json";
            File logFile = new File(outputDir, logFileName);

            File workDir = buildInfo.workingDirectory != null ? buildInfo.workingDirectory.toFile() : rootDir;

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode logData = mapper.createObjectNode();
            logData.put("buildSystem", buildInfo.buildSystem);
            logData.put("projectName", buildInfo.projectName);
            logData.put("success", result.success);
            logData.put("timestamp", result.timestamp);
            logData.put("workingDirectory", workDir.getAbsolutePath());
            logData.put("command", buildInfo.pluginCommand);
            logData.put("outputDirectory", outputDir.getAbsolutePath());

            if (result.exitCode != 0 || !result.success) {
                logData.put("exitCode", result.exitCode);
            }

            if (result.expectedSbomPath != null) {
                logData.put("expectedSbomPath", result.expectedSbomPath);
                logData.put("sbomFileExists", result.sbomFileExists);
                if (result.sbomFileExists) {
                    logData.put("sbomFileSize", result.sbomFileSize);
                }
            }

            ArrayNode buildFilesArray = mapper.createArrayNode();
            buildInfo.buildFiles.forEach(buildFilesArray::add);
            logData.set("buildFiles", buildFilesArray);

            logData.put("multiModule", buildInfo.multiModule);

            if (result.output != null && !result.output.trim().isEmpty()) {
                logData.put("stdout", result.output);
            }

            if (result.errorOutput != null && !result.errorOutput.trim().isEmpty()) {
                logData.put("stderr", result.errorOutput);
            }

            if (result.errorMessage != null && !result.errorMessage.trim().isEmpty()) {
                logData.put("errorMessage", result.errorMessage);
            }

            mapper.writerWithDefaultPrettyPrinter().writeValue(logFile, logData);
            System.out.println("Log file created: " + ConsoleColors.highlight(logFile.getAbsolutePath()));

        } catch (IOException e) {
            System.err.println(ConsoleColors.warning("[WARNING]") + " Failed to write SBOM log file: " + e.getMessage());
        }
    }

    private void writeAggregateLog(List<SbomGenerationResult> results) {
        try {
            File aggregateLogFile = new File(outputDir, "sbom-generation-aggregate.json");

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode aggregateData = mapper.createObjectNode();

            aggregateData.put("timestamp", java.time.Instant.now().toString());
            aggregateData.put("rootDirectory", rootDir.getAbsolutePath());
            aggregateData.put("outputDirectory", outputDir.getAbsolutePath());
            aggregateData.put("totalBuildSystems", results.size());

            int successCount = 0;
            int failureCount = 0;
            long totalSbomSize = 0;

            for (SbomGenerationResult result : results) {
                if (result.success) {
                    successCount++;
                    if (result.sbomFileExists) {
                        totalSbomSize += result.sbomFileSize;
                    }
                } else {
                    failureCount++;
                }
            }

            aggregateData.put("successfulGenerations", successCount);
            aggregateData.put("failedGenerations", failureCount);
            aggregateData.put("totalSbomSize", totalSbomSize);

            // Add summary array
            ArrayNode summaryArray = mapper.createArrayNode();
            for (SbomGenerationResult result : results) {
                ObjectNode summaryItem = mapper.createObjectNode();
                summaryItem.put("buildSystem", result.buildInfo.buildSystem);
                summaryItem.put("projectName", result.buildInfo.projectName);
                summaryItem.put("success", result.success);
                summaryItem.put("multiModule", result.buildInfo.multiModule);

                if (result.expectedSbomPath != null) {
                    summaryItem.put("sbomPath", result.expectedSbomPath);
                    summaryItem.put("sbomExists", result.sbomFileExists);
                    if (result.sbomFileExists) {
                        summaryItem.put("sbomSize", result.sbomFileSize);
                    }
                }

                if (!result.success && result.errorMessage != null) {
                    summaryItem.put("error", result.errorMessage);
                }

                if (result.exitCode != 0) {
                    summaryItem.put("exitCode", result.exitCode);
                }

                File workDir = result.buildInfo.workingDirectory != null ? result.buildInfo.workingDirectory.toFile() : rootDir;
                summaryItem.put("workingDirectory", workDir.getAbsolutePath());

                String logFileName = result.buildInfo.projectName + "-" + result.buildInfo.buildSystem.toLowerCase().replace(" ", "-") + "-sbom-log.json";
                summaryItem.put("detailedLogFile", logFileName);

                summaryArray.add(summaryItem);
            }

            aggregateData.set("buildSystems", summaryArray);

            mapper.writerWithDefaultPrettyPrinter().writeValue(aggregateLogFile, aggregateData);

            System.out.println(ConsoleColors.bold("\n=== SBOM Generation Summary ==="));
            System.out.println("Total build systems: " + results.size());
            System.out.println(ConsoleColors.success("Successful: " + successCount));
            if (failureCount > 0) {
                System.out.println(ConsoleColors.error("Failed: " + failureCount));
            } else {
                System.out.println("Failed: " + failureCount);
            }
            System.out.println("Aggregate log: " + ConsoleColors.highlight(aggregateLogFile.getAbsolutePath()));

        } catch (IOException e) {
            System.err.println(ConsoleColors.warning("[WARNING]") + " Failed to write aggregate log file: " + e.getMessage());
        }
    }

    private boolean prepareGoModule(BuildSystemInfo buildInfo) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File workDir = rootWorkingDir != null ? rootWorkingDir :
                      (buildInfo.workingDirectory != null ? buildInfo.workingDirectory.toFile() : rootDir);

        try {
            List<String> command = new ArrayList<>();

            if (isWindows) {
                command.add("cmd.exe");
                command.add("/c");
            } else {
                command.add("sh");
                command.add("-c");
            }

            Path vendorDir = workDir.toPath().resolve("vendor");
            String downloadCommand = Files.isDirectory(vendorDir)
                ? "go mod download -mod=vendor"
                : "go mod download";
            command.add(downloadCommand);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            System.out.println("[INFO] Downloading Go module dependencies...");
            Process process = pb.start();
            int exitCode = process.waitFor();

            return exitCode == 0;

        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String ensureRequiredTools(BuildSystemInfo buildInfo) {
        Optional<BuildSystemSbomGenerator> generatorOpt = BuildSystemGeneratorRegistry.getGenerator(buildInfo.buildSystem);
        if (generatorOpt.isEmpty()) {
            return null;
        }

        Path buildFile = null;
        if (buildInfo.buildFiles != null && !buildInfo.buildFiles.isEmpty()) {
            buildFile = Paths.get(buildInfo.buildFiles.get(0));
        }

        List<ToolRequirement> tools = generatorOpt.get().getRequiredTools(buildFile);
        if (tools.isEmpty()) {
            return null;
        }

        File workDir = rootWorkingDir != null ? rootWorkingDir :
            (buildInfo.workingDirectory != null ? buildInfo.workingDirectory.toFile() : rootDir);

        List<String> missing = new ArrayList<>();
        for (ToolRequirement tool : tools) {
            String checkCommand = tool.getCheckCommand();
            if (checkCommand == null || checkCommand.isBlank()) {
                continue;
            }
            if (isCommandAvailable(checkCommand)) {
                continue;
            }

            String installCommand = resolveInstallCommand(tool);
            if (allowToolInstall && installCommand != null && !installCommand.isBlank()) {
                System.out.println(ConsoleColors.info("[INFO]") + " Installing missing tool: " + tool.getName());
                boolean installOk = runCommand(installCommand, workDir);
                if (installOk && isCommandAvailable(checkCommand)) {
                    continue;
                }
            }
            missing.add(tool.getName());
        }

        if (missing.isEmpty()) {
            return null;
        }

        String message = "Required tool(s) missing for " + buildInfo.buildSystem + ": " + String.join(", ", missing);
        if (!allowToolInstall) {
            message += "\nRun with --allow-tool-install to install missing tools.";
        }
        return message;
    }

    private String resolveInstallCommand(ToolRequirement tool) {
        if ("cyclonedx-py".equals(tool.getName()) && isCommandAvailable("uv --version")) {
            return "uv tool install cyclonedx-bom";
        }
        if ("yarn".equals(tool.getName())) {
            if (isCommandAvailable("corepack --version")) {
                return "corepack prepare yarn@stable --activate";
            }
            if (isCommandAvailable("npm --version")) {
                return "npm install -g yarn";
            }
        }
        if ("pnpm".equals(tool.getName())) {
            if (isCommandAvailable("corepack --version")) {
                return "corepack prepare pnpm@latest --activate";
            }
            if (isCommandAvailable("npm --version")) {
                return "npm install -g pnpm";
            }
        }
        if ("CycloneDX".equals(tool.getName())) {
            Path toolManifest = findDotnetToolManifest();
            if (toolManifest != null) {
                return "dotnet tool restore";
            }
            return "dotnet tool install --global CycloneDX";
        }
        return tool.getInstallCommand();
    }

    private Path findDotnetToolManifest() {
        Path root = rootWorkingDir != null ? rootWorkingDir.toPath() : rootDir.toPath();
        Path manifest = root.resolve(".config").resolve("dotnet-tools.json");
        if (Files.exists(manifest)) {
            return manifest;
        }
        return null;
    }

    private boolean isCommandAvailable(String command) {
        if (command == null || command.isBlank()) {
            return true;
        }
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            List<String> cmd = new ArrayList<>();
            if (isWindows) {
                cmd.add("cmd.exe");
                cmd.add("/c");
            } else {
                cmd.add("sh");
                cmd.add("-c");
            }
            cmd.add(command);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private boolean runCommand(String command, File workDir) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            List<String> cmd = new ArrayList<>();
            if (isWindows) {
                cmd.add("cmd.exe");
                cmd.add("/c");
            } else {
                cmd.add("sh");
                cmd.add("-c");
            }
            cmd.add(command);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private SbomGenerationResult reuseSyftOutputIfAvailable(BuildSystemInfo buildInfo) {
        if (!isSyftCommand(buildInfo.pluginCommand)) {
            return null;
        }

        Path syftDir = getSyftDirectory(buildInfo);
        String cachedPath = syftOutputsByDir.get(syftDir);
        if (cachedPath == null) {
            return null;
        }

        File sbomFile = new File(cachedPath);
        if (!sbomFile.exists()) {
            syftOutputsByDir.remove(syftDir);
            return null;
        }

        SbomGenerationResult result = new SbomGenerationResult(buildInfo);
        result.success = true;
        result.exitCode = 0;
        result.expectedSbomPath = cachedPath;
        result.sbomFileExists = true;
        result.sbomFileSize = sbomFile.length();
        result.output = "Reused Syft output from " + cachedPath;
        System.out.println(ConsoleColors.info("[INFO]") + " Reusing Syft SBOM for " + syftDir);
        if (jsonOutput) {
            writeSbomLogFile(result);
        }
        return result;
    }

    private void cacheSyftOutput(BuildSystemInfo buildInfo, String outputPath) {
        if (!isSyftCommand(buildInfo.pluginCommand) || outputPath == null) {
            return;
        }
        Path syftDir = getSyftDirectory(buildInfo);
        syftOutputsByDir.put(syftDir, outputPath);
    }

    private Path getSyftDirectory(BuildSystemInfo buildInfo) {
        Path dir = buildInfo.workingDirectory != null ? buildInfo.workingDirectory : rootDir.toPath();
        return dir.toAbsolutePath().normalize();
    }

    private boolean isSyftCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return normalized.contains("syft ");
    }

    private void writeSummaryJson(List<BuildSystemInfo> buildSystems, boolean dryRun, List<Boolean> results) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("timestamp", new Date().toString());
        summary.put("rootDirectory", rootDir.getAbsolutePath());
        summary.put("outputDirectory", outputDir.getAbsolutePath());
        summary.put("buildSystemsDetected", buildSystems.size());
        summary.put("format", "json");
        summary.put("dryRun", dryRun);

        // Add details for each build system
        List<Map<String, Object>> buildSystemDetails = new ArrayList<>();
        for (int i = 0; i < buildSystems.size(); i++) {
            BuildSystemInfo buildInfo = buildSystems.get(i);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("buildSystem", buildInfo.buildSystem);
            details.put("projectName", buildInfo.projectName);
            details.put("multiModule", buildInfo.multiModule);
            details.put("buildFiles", buildInfo.buildFiles);
            details.put("workingDirectory", buildInfo.workingDirectory != null ? buildInfo.workingDirectory.toString() : null);
            details.put("pluginCommand", buildInfo.pluginCommand);
            if (!dryRun && i < results.size()) {
                details.put("generationSuccess", results.get(i));
            }
            buildSystemDetails.add(details);
        }
        summary.put("buildSystems", buildSystemDetails);

        if (!dryRun) {
            // Calculate overall success
            boolean allSuccess = results.stream().allMatch(Boolean::booleanValue);
            summary.put("overallSuccess", allSuccess);

            // List generated SBOM files (JSON only)
            List<String> generatedFiles = new ArrayList<>();
            try {
                if (outputDir.exists()) {
                    Files.list(outputDir.toPath())
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> p.getFileName().toString().contains("-bom"))
                        .forEach(p -> generatedFiles.add(p.toString()));
                }
            } catch (IOException e) {
                // Ignore
            }
            summary.put("generatedSbomFiles", generatedFiles);
        }

        ObjectMapper mapper = new ObjectMapper();
        File summaryFile = new File(outputDir, "sbom-summary.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(summaryFile, summary);
        System.out.println("\n" + ConsoleColors.success("[SUCCESS]") + " Summary written to " +
            ConsoleColors.highlight(summaryFile.getAbsolutePath()));
    }

    private boolean generateEvidenceOnlySbom(Map<String, List<Path>> foundFiles) {
        Map<String, List<Path>> evidenceFiles = collectEvidenceFiles(foundFiles);
        if (evidenceFiles.isEmpty()) {
            return false;
        }
        if (dryRun) {
            System.out.println(ConsoleColors.bold("\n[DRY-RUN]") + " Would generate evidence-only SBOM.");
            return true;
        }

        String projectName = rootDir.getName();
        if (projectName == null || projectName.isBlank()) {
            projectName = "project";
        }

        File outputFile = new File(outputDir, projectName + "-bom.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode bom = mapper.createObjectNode();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.6");
        bom.put("version", 1);

        ObjectNode metadata = bom.putObject("metadata");
        metadata.put("timestamp", java.time.Instant.now().toString());
        ObjectNode component = metadata.putObject("component");
        component.put("type", "application");
        component.put("name", projectName);

        ObjectNode evidence = component.putObject("evidence");
        ArrayNode occurrences = evidence.putArray("occurrences");
        for (List<Path> paths : evidenceFiles.values()) {
            for (Path path : paths) {
                ObjectNode occurrence = occurrences.addObject();
                occurrence.put("location", rootDir.toPath().relativize(path).toString());
            }
        }

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, bom);
            System.out.println(ConsoleColors.success("[SUCCESS]") + " Evidence-only SBOM generated.");
            System.out.println("Output location: " + ConsoleColors.highlight(outputFile.getAbsolutePath()));
            return true;
        } catch (IOException e) {
            System.err.println(ConsoleColors.error("[ERROR]") + " Failed to write evidence-only SBOM: " + e.getMessage());
            return false;
        }
    }

    private Map<String, List<Path>> collectEvidenceFiles(Map<String, List<Path>> foundFiles) {
        Map<String, List<Path>> evidence = new LinkedHashMap<>();
        for (Map.Entry<String, List<Path>> entry : foundFiles.entrySet()) {
            String key = entry.getKey();
            if (BuildSystemGeneratorRegistry.isSupported(key)) {
                continue;
            }
            if (!isEvidenceCategory(key)) {
                continue;
            }
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                evidence.put(key, entry.getValue());
            }
        }
        return evidence;
    }

    private boolean isEvidenceCategory(String key) {
        return Set.of(
            "Terraform",
            "CloudFormation",
            "Ansible",
            "Kubernetes",
            "Docker",
            "Pulumi",
            "YAML/JSON Config"
        ).contains(key);
    }

    private void mergeSboms() throws IOException {
        Path outputPath = outputDir.toPath();

        // Find all JSON SBOM files in the output directory
        List<Path> sbomFiles = Files.list(outputPath)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .filter(p -> p.getFileName().toString().contains("-bom"))
                .collect(Collectors.toList());

        if (sbomFiles.isEmpty()) {
            System.out.println("No SBOM files found to merge");
            return;
        }

        if (sbomFiles.size() == 1) {
            System.out.println("Only one SBOM file found, no merge needed");
            return;
        }

        System.out.println("Found " + sbomFiles.size() + " SBOM files to merge:");
        sbomFiles.forEach(f -> System.out.println("  - " + f.getFileName()));

        // Always use JSON format
        mergeJsonSboms(sbomFiles, outputPath);
    }

    private void mergeJsonSboms(List<Path> sbomFiles, Path outputPath) throws IOException {
        // For JSON, we'll create a simple merged structure
        // In production, you'd use a proper CycloneDX library
        StringBuilder merged = new StringBuilder();
        merged.append("{\n");
        merged.append("  \"bomFormat\": \"CycloneDX\",\n");
        merged.append("  \"specVersion\": \"1.5\",\n");
        merged.append("  \"version\": 1,\n");
        merged.append("  \"components\": [\n");

        boolean first = true;
        for (Path sbomFile : sbomFiles) {
            String content = Files.readString(sbomFile);
            // Extract components array (simplified - real implementation would use JSON parser)
            int componentsStart = content.indexOf("\"components\"");
            if (componentsStart > 0) {
                int arrayStart = content.indexOf("[", componentsStart);
                int arrayEnd = content.indexOf("]", arrayStart);
                if (arrayStart > 0 && arrayEnd > 0) {
                    String components = content.substring(arrayStart + 1, arrayEnd).trim();
                    if (!components.isEmpty()) {
                        if (!first) {
                            merged.append(",\n");
                        }
                        merged.append("    ").append(components);
                        first = false;
                    }
                }
            }
        }

        merged.append("\n  ]\n");
        merged.append("}\n");

        Path mergedFile = outputPath.resolve("merged-bom.json");
        Files.writeString(mergedFile, merged.toString());
        System.out.println("\n[SUCCESS] Merged SBOM created: " + mergedFile.getFileName());
        System.out.println("Location: " + mergedFile.toAbsolutePath());
    }
}
