package org.hoggmania.generators;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.hoggmania.BuildSystemSbomGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generator for creating SBOMs from standalone JAR files.
 * Scans the project for JARs, computes SHA1 hashes, looks them up in Maven Central,
 * and generates a CycloneDX SBOM.
 */
public class JarSbomGenerator implements BuildSystemSbomGenerator {

    private static final String MAVEN_CENTRAL_SEARCH_URL = "https://search.maven.org/solrsearch/select?q=1:\"%s\"&rows=20&wt=json";
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private MessageDigest sha1Digest;

    @RegisterForReflection
    public static class JarInfo {
        private String path;
        private String sha1;
        private List<MavenArtifact> artifacts = new ArrayList<>();

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        
        public String getSha1() { return sha1; }
        public void setSha1(String sha1) { this.sha1 = sha1; }
        
        public List<MavenArtifact> getArtifacts() { return artifacts; }
        public void setArtifacts(List<MavenArtifact> artifacts) { this.artifacts = artifacts; }
    }

    @RegisterForReflection
    public static class MavenArtifact {
        private String groupId;
        private String artifactId;
        private String version;

        public MavenArtifact() {}

        public MavenArtifact(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        
        public String getArtifactId() { return artifactId; }
        public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getPurl() {
            return String.format("pkg:maven/%s/%s@%s", groupId, artifactId, version);
        }
    }

    @RegisterForReflection
    public static class MavenSearchResponse {
        private Response response;

        public Response getResponse() { return response; }
        public void setResponse(Response response) { this.response = response; }

        @RegisterForReflection
        public static class Response {
            private int numFound;
            private List<Doc> docs = new ArrayList<>();

            public int getNumFound() { return numFound; }
            public void setNumFound(int numFound) { this.numFound = numFound; }
            
            public List<Doc> getDocs() { return docs; }
            public void setDocs(List<Doc> docs) { this.docs = docs; }
        }

        @RegisterForReflection
        public static class Doc {
            private String g; // groupId
            private String a; // artifactId
            private String v; // version

            public String getG() { return g; }
            public void setG(String g) { this.g = g; }
            
            public String getA() { return a; }
            public void setA(String a) { this.a = a; }
            
            public String getV() { return v; }
            public void setV(String v) { this.v = v; }
        }
    }

    public JarSbomGenerator() {
        // Don't initialize HttpClient here - lazy initialize to avoid GraalVM issues
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return httpClient;
    }

    private ObjectMapper getObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        return objectMapper;
    }

    private MessageDigest getSha1Digest() {
        if (sha1Digest == null) {
            try {
                sha1Digest = MessageDigest.getInstance("SHA-1");
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize SHA-1 digest: " + e.getMessage(), e);
            }
        }
        return sha1Digest;
    }

    @Override
    public String getBuildSystemName() {
        return "Standalone JARs";
    }

    @Override
    public String getBuildFilePattern() {
        // Return a pattern that won't match anything during normal scanning
        // We'll handle JAR detection specially in SbomCommand
        return "___STANDALONE_JARS___";
    }

    @Override
    public List<String> getExcludedDirectories() {
        // Exclude common build directories when looking for JARs
        List<String> excluded = new ArrayList<>();
        excluded.add("target");
        excluded.add("build");
        excluded.add(".m2");
        excluded.add(".gradle");
        excluded.add("node_modules");
        return excluded;
    }

    @Override
    public String getVersionCheckCommand() {
        // No version to check for standalone JARs
        return "echo Standalone JARs";
    }

    @Override
    @Deprecated
    public String generateSbomCommand(String projectName, File outputDir) {
        return generateSbomCommand(projectName, outputDir, null);
    }

    @Override
    public String generateSbomCommand(String projectName, File outputDir, Path buildFile) {
        // Generate a Java command that calls our JAR scanner
        // This will be executed by SbomCommand
        String jarScannerClass = "org.hoggmania.generators.JarSbomGenerator$Scanner";
        String outputFile = new File(outputDir, projectName + "-bom.json").getAbsolutePath();
        String rootPath = (buildFile != null ? buildFile : Path.of(".")).toAbsolutePath().toString();
        
        // Return a Java command that runs our scanner
        return String.format("java -cp \"${CLASSPATH}\" %s \"%s\" \"%s\" \"%s\"",
                jarScannerClass, rootPath, outputFile, projectName);
    }

    @Override
    public String extractProjectName(Path buildFile) {
        // buildFile is actually the root directory for JAR generator
        if (buildFile == null) return "unknown-project";
        return buildFile.getFileName().toString();
    }

    /**
     * Find all JAR files in the project (used by SbomCommand to detect this generator).
     * Returns a list containing the root directory if JARs are found and no package manager exists.
     */
    public List<Path> findBuildFiles(Path rootDir) throws IOException {
        // Find all JAR files in the project, excluding target/build directories
        List<Path> jarFiles = Files.walk(rootDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .filter(p -> !p.toString().contains("target" + File.separator))
                .filter(p -> !p.toString().contains("build" + File.separator))
                .filter(p -> !p.toString().contains(".m2" + File.separator))
                .filter(p -> !p.toString().contains(".gradle" + File.separator))
                .collect(Collectors.toList());

        // Only return a result if we found JARs and there's no package manager
        if (!jarFiles.isEmpty() && !hasPackageManager(rootDir)) {
            // Return the root directory as the "build file" location
            return List.of(rootDir);
        }
        return List.of();
    }

    /**
     * Check if the project has a recognized package manager.
     */
    private boolean hasPackageManager(Path rootDir) throws IOException {
        return Files.exists(rootDir.resolve("pom.xml")) ||
               Files.exists(rootDir.resolve("build.gradle")) ||
               Files.exists(rootDir.resolve("build.gradle.kts")) ||
               Files.exists(rootDir.resolve("package.json")) ||
               Files.exists(rootDir.resolve("requirements.txt")) ||
               Files.exists(rootDir.resolve("setup.py")) ||
               Files.exists(rootDir.resolve("go.mod")) ||
               Files.exists(rootDir.resolve("Cargo.toml")) ||
               Files.exists(rootDir.resolve("composer.json")) ||
               Files.find(rootDir, 2, (p, attr) -> 
                   p.getFileName().toString().endsWith(".csproj") ||
                   p.getFileName().toString().endsWith(".gemspec")).findAny().isPresent();
    }

    /**
     * Find all JAR files in the project and compute their metadata.
     */
    public List<JarInfo> findJars(Path rootDir) throws IOException {
        List<Path> jarFiles = Files.walk(rootDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .filter(p -> !p.toString().contains("target" + File.separator))
                .filter(p -> !p.toString().contains("build" + File.separator))
                .filter(p -> !p.toString().contains(".m2" + File.separator))
                .filter(p -> !p.toString().contains(".gradle" + File.separator))
                .collect(Collectors.toList());

        List<JarInfo> jarInfos = new ArrayList<>();
        for (Path jarPath : jarFiles) {
            JarInfo info = new JarInfo();
            info.setPath(rootDir.relativize(jarPath).toString());
            info.setSha1(computeSha1(jarPath));
            jarInfos.add(info);
        }

        return jarInfos;
    }

    /**
     * Compute SHA1 hash of a file.
     */
    private String computeSha1(Path filePath) throws IOException {
        try {
            MessageDigest digest = getSha1Digest();
            digest.reset(); // Reset for reuse
            try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                byte[] byteArray = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Failed to compute SHA1: " + e.getMessage(), e);
        }
    }

    /**
     * Lookup JAR in Maven Central by SHA1 hash.
     */
    public List<MavenArtifact> lookupInMavenCentral(String sha1) throws IOException, InterruptedException {
        String url = String.format(MAVEN_CENTRAL_SEARCH_URL, URLEncoder.encode(sha1, StandardCharsets.UTF_8));
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Maven Central search failed with status: " + response.statusCode());
        }

        List<MavenArtifact> artifacts = new ArrayList<>();
        try {
            MavenSearchResponse searchResponse = getObjectMapper().readValue(response.body(), MavenSearchResponse.class);
            if (searchResponse.getResponse() != null && searchResponse.getResponse().getDocs() != null) {
                for (MavenSearchResponse.Doc doc : searchResponse.getResponse().getDocs()) {
                    artifacts.add(new MavenArtifact(doc.getG(), doc.getA(), doc.getV()));
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse Maven Central response: " + e.getMessage(), e);
        }

        return artifacts;
    }

    /**
     * Generate a CycloneDX SBOM from JAR information.
     */
    public void generateSbom(List<JarInfo> jarInfos, String projectName, File outputFile) throws IOException {
        StringBuilder sbom = new StringBuilder();
        
        // CycloneDX 1.4 JSON header
        sbom.append("{\n");
        sbom.append("  \"bomFormat\": \"CycloneDX\",\n");
        sbom.append("  \"specVersion\": \"1.4\",\n");
        sbom.append("  \"version\": 1,\n");
        sbom.append("  \"metadata\": {\n");
        sbom.append("    \"component\": {\n");
        sbom.append("      \"type\": \"application\",\n");
        sbom.append("      \"name\": \"").append(escapeJson(projectName)).append("\"\n");
        sbom.append("    }\n");
        sbom.append("  },\n");
        sbom.append("  \"components\": [\n");

        boolean first = true;
        for (JarInfo jarInfo : jarInfos) {
            if (jarInfo.getArtifacts().isEmpty()) {
                // No artifacts found - use pkg:maven/unknown@sha1
                if (!first) sbom.append(",\n");
                sbom.append("    {\n");
                sbom.append("      \"type\": \"library\",\n");
                sbom.append("      \"name\": \"").append(escapeJson(jarInfo.getPath())).append("\",\n");
                sbom.append("      \"purl\": \"pkg:maven/unknown@").append(jarInfo.getSha1()).append("\",\n");
                sbom.append("      \"hashes\": [\n");
                sbom.append("        {\n");
                sbom.append("          \"alg\": \"SHA-1\",\n");
                sbom.append("          \"content\": \"").append(jarInfo.getSha1()).append("\"\n");
                sbom.append("        }\n");
                sbom.append("      ],\n");
                sbom.append("      \"properties\": [\n");
                sbom.append("        {\n");
                sbom.append("          \"name\": \"file:location\",\n");
                sbom.append("          \"value\": \"").append(escapeJson(jarInfo.getPath())).append("\"\n");
                sbom.append("        }\n");
                sbom.append("      ]\n");
                sbom.append("    }");
                first = false;
            } else {
                // Add all found artifacts
                for (MavenArtifact artifact : jarInfo.getArtifacts()) {
                    if (!first) sbom.append(",\n");
                    sbom.append("    {\n");
                    sbom.append("      \"type\": \"library\",\n");
                    sbom.append("      \"group\": \"").append(escapeJson(artifact.getGroupId())).append("\",\n");
                    sbom.append("      \"name\": \"").append(escapeJson(artifact.getArtifactId())).append("\",\n");
                    sbom.append("      \"version\": \"").append(escapeJson(artifact.getVersion())).append("\",\n");
                    sbom.append("      \"purl\": \"").append(escapeJson(artifact.getPurl())).append("\",\n");
                    sbom.append("      \"hashes\": [\n");
                    sbom.append("        {\n");
                    sbom.append("          \"alg\": \"SHA-1\",\n");
                    sbom.append("          \"content\": \"").append(jarInfo.getSha1()).append("\"\n");
                    sbom.append("        }\n");
                    sbom.append("      ],\n");
                    sbom.append("      \"properties\": [\n");
                    sbom.append("        {\n");
                    sbom.append("          \"name\": \"file:location\",\n");
                    sbom.append("          \"value\": \"").append(escapeJson(jarInfo.getPath())).append("\"\n");
                    sbom.append("        }\n");
                    sbom.append("      ]\n");
                    sbom.append("    }");
                    first = false;
                }
            }
        }

        sbom.append("\n  ]\n");
        sbom.append("}\n");

        Files.writeString(outputFile.toPath(), sbom.toString());
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    /**
     * Main method for running as a standalone scanner (called via command line).
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: JarSbomGenerator <root-dir> <output-file> <project-name>");
            System.exit(1);
        }

        Path rootDir = Path.of(args[0]);
        File outputFile = new File(args[1]);
        String projectName = args[2];

        JarSbomGenerator generator = new JarSbomGenerator();
        
        // Find all JARs
        List<JarInfo> jarInfos = generator.findJars(rootDir);
        
        if (jarInfos.isEmpty()) {
            System.out.println("No JAR files found in project");
            System.exit(0);
        }

        System.out.println("Found " + jarInfos.size() + " JAR file(s):");
        
        // Lookup each JAR in Maven Central
        for (JarInfo jarInfo : jarInfos) {
            System.out.println("  " + jarInfo.getPath() + " (SHA1: " + jarInfo.getSha1() + ")");
            try {
                List<MavenArtifact> artifacts = generator.lookupInMavenCentral(jarInfo.getSha1());
                jarInfo.setArtifacts(artifacts);
                
                if (artifacts.isEmpty()) {
                    System.out.println("    → Not found in Maven Central, will use pkg:maven/unknown@" + jarInfo.getSha1());
                } else {
                    System.out.println("    → Found " + artifacts.size() + " artifact(s) in Maven Central:");
                    for (MavenArtifact artifact : artifacts) {
                        System.out.println("       - " + artifact.getPurl());
                    }
                }
            } catch (Exception e) {
                System.err.println("    → Failed to lookup in Maven Central: " + e.getMessage());
                // Leave artifacts empty - will use unknown
            }
        }

        // Ensure output directory exists
        outputFile.getParentFile().mkdirs();

        // Generate SBOM
        generator.generateSbom(jarInfos, projectName, outputFile);
        
        System.out.println("\nSBOM generated: " + outputFile.getAbsolutePath());
    }
}
