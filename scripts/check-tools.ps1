#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Verify that all required build tools, package managers, and SBOM utilities are installed.
.DESCRIPTION
    Iterates over the known toolchain (Maven, Gradle, npm/yarn/pnpm, Python ecosystem, .NET/Rust/PHP/Ruby helpers, Syft, etc.)
    and attempts to install any missing binaries automatically via the available package manager or language-specific installer.
#>

$ErrorActionPreference = 'Stop'

function Write-Status([string]$message) {
    Write-Host "[INFO]  $message"
}

function Write-WarningMessage([string]$message) {
    Write-Host "[WARN]  $message"
}

function Is-CommandAvailable([string[]]$names) {
    foreach ($name in $names) {
        if ([string]::IsNullOrWhiteSpace($name)) {
            continue
        }
        if (Get-Command $name -ErrorAction SilentlyContinue) {
            return $true
        }
    }
    return $false
}

function Invoke-PackageManagerInstall([array]$candidates) {
    foreach ($candidate in $candidates) {
        $manager = $candidate.Manager
        if ([string]::IsNullOrWhiteSpace($manager)) {
            continue
        }
        if (Get-Command $manager -ErrorAction SilentlyContinue) {
            Write-Status "Installing with $manager: $($candidate.Command)"
            Invoke-Expression $candidate.Command
            return $true
        }
    }
    return $false
}

function Get-PythonExecutable() {
    if (Get-Command python -ErrorAction SilentlyContinue) {
        return 'python'
    }
    if (Get-Command python3 -ErrorAction SilentlyContinue) {
        return 'python3'
    }
    return $null
}

function Install-Conda {
    $candidates = @(
        @{ Manager = 'winget'; Command = 'winget install --id Miniconda.Miniconda3 -e --accept-package-agreements --accept-source-agreements' },
        @{ Manager = 'choco'; Command = 'choco install miniconda3 -y' },
        @{ Manager = 'scoop'; Command = 'scoop install miniconda3' }
    )
    if (Invoke-PackageManagerInstall $candidates) {
        return
    }

    if (-not $IsWindows) {
        $installerPath = Join-Path $env:TEMP 'miniconda_installer.sh'
        if (Test-Path $installerPath) { Remove-Item $installerPath -Force }
        if (Get-Command curl -ErrorAction SilentlyContinue) {
            Invoke-Expression "curl -fsSLo `"$installerPath`" https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh"
        } elseif (Get-Command wget -ErrorAction SilentlyContinue) {
            Invoke-Expression "wget -O `"$installerPath`" https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh"
        } else {
            Write-WarningMessage 'curl or wget is required to download Miniconda. Please install Conda manually.'
            return
        }
        bash -c "bash `"$installerPath`" -b -p `"$HOME/miniconda`""
        Write-Status 'Conda installed via Miniconda installer. Please add $HOME/miniconda/bin to your PATH.'
        return
    }

    Write-WarningMessage 'Conda could not be installed automatically. Please install Miniconda/Anaconda manually.'
}

function Ensure-Tool([string]$displayName, [string[]]$aliases, [ScriptBlock]$installer) {
    if (Is-CommandAvailable $aliases) {
        Write-Status "$displayName already available."
        return
    }
    Write-Status "$displayName is missing, attempting to install..."
    & $installer
}

$tools = @(
    [PSCustomObject]@{
        Key = 'python'
        Display = 'Python 3'
        Aliases = @('python', 'python3')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id Python.Python.3 -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install python -y' },
                @{ Manager = 'scoop'; Command = 'scoop install python' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y python3 python3-pip' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y python3 python3-pip' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y python3 python3-pip' },
                @{ Manager = 'brew'; Command = 'brew install python' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Please install Python 3 manually (https://www.python.org/downloads).'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'maven'
        Display = 'Apache Maven'
        Aliases = @('mvn')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id Apache.Maven -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install maven -y' },
                @{ Manager = 'scoop'; Command = 'scoop install maven' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y maven' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y maven' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y maven' },
                @{ Manager = 'brew'; Command = 'brew install maven' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Install Maven manually: https://maven.apache.org/install.html'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'gradle'
        Display = 'Gradle'
        Aliases = @('gradle')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id Gradle.Gradle -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install gradle -y' },
                @{ Manager = 'scoop'; Command = 'scoop install gradle' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y gradle' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y gradle' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y gradle' },
                @{ Manager = 'brew'; Command = 'brew install gradle' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Install Gradle manually: https://gradle.org/install/'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'npm'
        Display = 'npm (via Node.js LTS)'
        Aliases = @('npm')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id OpenJS.NodeJS.LTS -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install nodejs-lts -y' },
                @{ Manager = 'scoop'; Command = 'scoop install nodejs-lts' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y nodejs npm' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y nodejs npm' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y nodejs npm' },
                @{ Manager = 'brew'; Command = 'brew install node' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Install Node.js (includes npm) manually: https://nodejs.org/en/download/'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'yarn'
        Display = 'Yarn'
        Aliases = @('yarn')
        Installer = {
            if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
                Write-WarningMessage 'npm is required to install Yarn. Install npm first.'
                return
            }
            Write-Status 'Installing Yarn via npm'
            npm install -g yarn
        }
    },
    [PSCustomObject]@{
        Key = 'pnpm'
        Display = 'pnpm'
        Aliases = @('pnpm')
        Installer = {
            if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
                Write-WarningMessage 'npm is required to install pnpm. Install npm first.'
                return
            }
            Write-Status 'Installing pnpm via npm'
            npm install -g pnpm
        }
    },
    [PSCustomObject]@{
        Key = 'pipenv'
        Display = 'pipenv'
        Aliases = @('pipenv')
        Installer = {
            $python = Get-PythonExecutable
            if (-not $python) {
                Write-WarningMessage 'Python is required before installing pipenv.'
                return
            }
            Write-Status 'Installing pipenv via pip'
            & $python -m pip install --user pipenv
        }
    },
    [PSCustomObject]@{
        Key = 'poetry'
        Display = 'poetry'
        Aliases = @('poetry')
        Installer = {
            $python = Get-PythonExecutable
            if (-not $python) {
                Write-WarningMessage 'Python is required before installing poetry.'
                return
            }
            Write-Status 'Installing poetry via pip'
            & $python -m pip install --user poetry
        }
    },
    [PSCustomObject]@{
        Key = 'uv'
        Display = 'uv'
        Aliases = @('uv')
        Installer = {
            $python = Get-PythonExecutable
            if (-not $python) {
                Write-WarningMessage 'Python is required before installing uv.'
                return
            }
            Write-Status 'Installing uv and cyclonedx-py via pip'
            & $python -m pip install --user uv cyclonedx-bom
        }
    },
    [PSCustomObject]@{
        Key = 'conda'
        Display = 'Conda (Miniconda/Anaconda)'
        Aliases = @('conda')
        Installer = { Install-Conda }
    },
    [PSCustomObject]@{
        Key = 'go'
        Display = 'Go'
        Aliases = @('go')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id GoLang.Go -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install golang -y' },
                @{ Manager = 'scoop'; Command = 'scoop install golang' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y golang' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y golang' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y golang' },
                @{ Manager = 'brew'; Command = 'brew install go' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Install Go manually: https://go.dev/doc/install'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'dotnet'
        Display = '.NET SDK'
        Aliases = @('dotnet')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id Microsoft.DotNet.SDK.8 -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install dotnet-8.0-sdk -y' },
                @{ Manager = 'scoop'; Command = 'scoop install dotnet-sdk' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y dotnet-sdk-8.0' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y dotnet-sdk-8.0' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y dotnet-sdk-8.0' },
                @{ Manager = 'brew'; Command = 'brew install --cask dotnet-sdk' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Install .NET SDK manually: https://dotnet.microsoft.com/en-us/download'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'cargo'
        Display = 'Cargo & Rust toolchain'
        Aliases = @('cargo')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id RustLang.Rustup -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install rustup -y' },
                @{ Manager = 'scoop'; Command = 'scoop install rustup' }
            )
            if (Invoke-PackageManagerInstall $candidates) {
                return
            }
            if (-not $IsWindows) {
                if (Get-Command curl -ErrorAction SilentlyContinue) {
                    bash -c 'curl --proto "=https" --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y'
                    return
                }
                if (Get-Command wget -ErrorAction SilentlyContinue) {
                    bash -c 'wget https://sh.rustup.rs -O - | sh -s -- -y'
                    return
                }
            }
            Write-WarningMessage 'Install Rust manually: https://www.rust-lang.org/tools/install'
        }
    },
    [PSCustomObject]@{
        Key = 'composer'
        Display = 'Composer'
        Aliases = @('composer')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id Composer -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install composer -y' },
                @{ Manager = 'scoop'; Command = 'scoop install composer' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y composer' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y composer' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y composer' },
                @{ Manager = 'brew'; Command = 'brew install composer' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Install Composer manually: https://getcomposer.org/download/'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'gem'
        Display = 'Ruby Gem'
        Aliases = @('gem')
        Installer = {
            $candidates = @(
                @{ Manager = 'winget'; Command = 'winget install --id RubyInstaller.Ruby.3 -e --accept-package-agreements --accept-source-agreements' },
                @{ Manager = 'choco'; Command = 'choco install ruby -y' },
                @{ Manager = 'scoop'; Command = 'scoop install ruby' },
                @{ Manager = 'apt-get'; Command = 'sudo apt-get install -y ruby-full' },
                @{ Manager = 'dnf'; Command = 'sudo dnf install -y ruby' },
                @{ Manager = 'yum'; Command = 'sudo yum install -y ruby' },
                @{ Manager = 'brew'; Command = 'brew install ruby' }
            )
            if (-not (Invoke-PackageManagerInstall $candidates)) {
                Write-WarningMessage 'Install Ruby manually: https://www.ruby-lang.org/en/documentation/installation/'
            }
        }
    },
    [PSCustomObject]@{
        Key = 'syft'
        Display = 'Syft'
        Aliases = @('syft')
        Installer = {
            if ($IsWindows) {
                Write-Status 'Installing Syft via PowerShell installer'
                Invoke-Expression (Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/anchore/syft/main/install.ps1').Content
                return
            }
            if (Get-Command curl -ErrorAction SilentlyContinue) {
                bash -c 'curl -sSfL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin'
                return
            }
            if (Get-Command wget -ErrorAction SilentlyContinue) {
                bash -c 'wget -qO- https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin'
                return
            }
            Write-WarningMessage 'Install Syft manually: https://github.com/anchore/syft/blob/main/docs/install.md'
        }
    },
    [PSCustomObject]@{
        Key = 'cyclonedx-py'
        Display = 'cyclonedx-py (CycloneDX Python)'
        Aliases = @('cyclonedx-py')
        Installer = {
            $python = Get-PythonExecutable
            if (-not $python) {
                Write-WarningMessage 'Python is required before installing cyclonedx-py.'
                return
            }
            Write-Status 'Installing cyclonedx-py via pip'
            & $python -m pip install --user cyclonedx-bom
        }
    },
    [PSCustomObject]@{
        Key = 'cyclonedx-ruby'
        Display = 'cyclonedx-ruby'
        Aliases = @('cyclonedx-ruby')
        Installer = {
            if (-not (Get-Command gem -ErrorAction SilentlyContinue)) {
                Write-WarningMessage 'gem is required before installing cyclonedx-ruby.'
                return
            }
            Write-Status 'Installing cyclonedx-ruby via gem'
            gem install cyclonedx-ruby
        }
    },
    [PSCustomObject]@{
        Key = 'cargo-cyclonedx'
        Display = 'cargo-cyclonedx'
        Aliases = @('cargo-cyclonedx')
        Installer = {
            if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
                Write-WarningMessage 'cargo is required before installing cargo-cyclonedx.'
                return
            }
            Write-Status 'Installing cargo-cyclonedx via cargo'
            cargo install cargo-cyclonedx
        }
    },
    [PSCustomObject]@{
        Key = 'dotnet-cyclonedx'
        Display = 'CycloneDX .NET tool'
        Aliases = @('CycloneDX')
        Installer = {
            if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
                Write-WarningMessage '.NET is required before installing the CycloneDX tool.'
                return
            }
            Write-Status 'Installing CycloneDX dotnet tool'
            dotnet tool install --global CycloneDX
        }
    },
    [PSCustomObject]@{
        Key = 'php-cyclonedx-plugin'
        Display = 'CycloneDX PHP Composer plugin'
        Aliases = @('composer')
        Installer = {
            if (-not (Get-Command composer -ErrorAction SilentlyContinue)) {
                Write-WarningMessage 'composer is required before installing the CycloneDX plugin.'
                return
            }
            Write-Status 'Installing CycloneDX PHP Composer plugin'
            composer global require cyclonedx/cyclonedx-php-composer
        }
    }
)

foreach ($tool in $tools) {
    Ensure-Tool $tool.Display $tool.Aliases $tool.Installer
}

Write-Status 'Toolchain check complete.'
