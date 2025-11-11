# Build Environment Intelligence (BEI) - v1.0.0

## 🎉 Initial Release

We're excited to announce the first release of **Build Environment Intelligence (BEI)**, a powerful Quarkus-based CLI tool for build environment analysis and SBOM (Software Bill of Materials) generation.

## 🚀 Key Features

### Environment Scanning
- **Multi-Build System Detection**: Automatically detects 9 build systems:
  - Maven
  - Gradle
  - npm
  - Python (setup.py, requirements.txt)
  - Go (go.mod)
  - .NET (.csproj, .vbproj, .fsproj)
  - Rust (Cargo.toml)
  - PHP (composer.json)
  - Ruby (Gemfile)

- **Tool Version Detection**: Identifies installed versions of build tools in your environment
- **Multi-Module Project Support**: Intelligently filters child modules to avoid duplicate SBOM generation
- **File Type Analysis**: Counts and categorizes source files in your project

### SBOM Generation
- **CycloneDX Format**: Generates industry-standard CycloneDX SBOMs in JSON format
- **Multi-System Support**: Generates SBOMs for all detected build systems in a single run
- **Polyglot Projects**: Automatically merges SBOMs when multiple build systems are detected
- **Absolute Path Commands**: Uses explicit paths for all build system commands, ensuring reliability

### Cross-Platform Support
- **Native Executables**: Fast startup times with GraalVM native compilation
- **JVM Mode**: Traditional uber-JAR for maximum compatibility
- **Windows & Linux**: Tested on both platforms with appropriate terminal detection
- **Smart Color Output**: Automatically detects terminal capabilities and enables ANSI colors only when supported (Windows Terminal, VS Code, Unix terminals)

## 📦 Available Artifacts

This release includes two build variants:

- **`bei-jvm`**: Uber-JAR for running on any JVM (Java 21+)
  - Usage: `java -jar build.env.intel-1.0.0-SNAPSHOT-runner.jar <command>`
  
- **`bei-native`**: Native executable with instant startup
  - Usage: `./build.env.intel-1.0.0-SNAPSHOT-runner <command>` (Linux)
  - Usage: `build.env.intel-1.0.0-SNAPSHOT-runner.exe <command>` (Windows)

## 🎯 Usage Examples

### Scan your build environment:
```bash
bei scan
bei scan --output my-scan-results.json
```

### Generate SBOMs:
```bash
bei sbom                    # Generate for all detected build systems
bei sbom --dry-run          # Preview what would be generated
bei sbom --merge            # Merge multiple SBOMs into one
bei sbom --output ./sboms   # Specify output directory
```

## ✨ Highlights

- **Zero Configuration**: Just run it in your project directory
- **Intelligent Detection**: Automatically finds build files and filters multi-module projects
- **Comprehensive Logging**: Detailed logs for troubleshooting SBOM generation
- **Color-Coded Output**: Easy-to-read terminal output with success/error/warning indicators
- **Professional CLI**: Built with Picocli for a polished command-line experience

## 🛠️ Technical Details

- **Framework**: Quarkus 3.29.1
- **Java Version**: 21 LTS
- **Build Tool**: Maven 3.9.11
- **Native Compilation**: GraalVM 25.0.1 / Mandrel 23.1.9.0
- **Binary Size**: ~27MB (native executable)
- **Startup Time**: <100ms (native), ~1s (JVM)

## 📋 Requirements

- **For JVM**: Java 21 or later
- **For Native**: No dependencies - fully self-contained executable

## 🔧 Build Systems & CycloneDX Plugins

BEI integrates with the following CycloneDX plugins:
- Maven: `cyclonedx-maven-plugin`
- Gradle: `cyclonedxBom` task
- npm: `@cyclonedx/cyclonedx-npm`
- Python: `cyclonedx-py`
- Go: `cyclonedx-gomod`
- .NET: `CycloneDX`
- Rust: `cargo-cyclonedx`
- PHP: `cyclonedx-php-composer`
- Ruby: `cyclonedx-ruby`

## 🐛 Known Limitations

- Requires respective build tools to be installed and accessible in PATH
- Multi-module Maven projects generate SBOM only for root POM (by design)
- Multi-module Gradle projects generate SBOM only for root build.gradle (by design)
- Color output disabled in legacy terminals (PowerShell 5.1) to avoid garbled characters

## 🙏 Acknowledgments

Built with:
- [Quarkus](https://quarkus.io/) - Supersonic Subatomic Java
- [Picocli](https://picocli.info/) - CLI framework
- [GraalVM](https://www.graalvm.org/) - Native compilation
- [CycloneDX](https://cyclonedx.org/) - SBOM standard

## 📄 License

MIT License - See LICENSE file for details

---

**Note**: This is a snapshot release (1.0.0-SNAPSHOT). Consider it a preview/beta version for testing and feedback.
