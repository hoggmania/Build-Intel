#!/usr/bin/env bash
set -euo pipefail

log_info() {
  echo "[INFO]  $*"
}

log_warn() {
  echo "[WARN]  $*"
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

is_python_available() {
  command_exists python3 || command_exists python
}

python_bin() {
  if command_exists python3; then
    echo "python3"
    return
  fi
  if command_exists python; then
    echo "python"
    return
  fi
  return 1
}

install_with_package_manager() {
  local packages="$1"
  if command_exists apt-get; then
    if sudo apt-get install -y ${packages}; then
      return 0
    fi
  fi
  if command_exists dnf; then
    if sudo dnf install -y ${packages}; then
      return 0
    fi
  fi
  if command_exists yum; then
    if sudo yum install -y ${packages}; then
      return 0
    fi
  fi
  return 1
}

install_python() {
  if install_with_package_manager "python3 python3-pip"; then
    return 0
  fi
  if command_exists brew; then
    brew install python
    return 0
  fi
  return 1
}

install_maven() {
  if install_with_package_manager "maven"; then
    return 0
  fi
  if command_exists brew; then
    brew install maven
    return 0
  fi
  return 1
}

install_gradle() {
  if install_with_package_manager "gradle"; then
    return 0
  fi
  if command_exists brew; then
    brew install gradle
    return 0
  fi
  return 1
}

install_node() {
  if install_with_package_manager "nodejs npm"; then
    return 0
  fi
  if command_exists brew; then
    brew install node
    return 0
  fi
  return 1
}

install_yarn() {
  if ! command_exists npm; then
    return 1
  fi
  npm install -g yarn
  return 0
}

install_pnpm() {
  if ! command_exists npm; then
    return 1
  fi
  npm install -g pnpm
  return 0
}

install_pipenv() {
  local py
  if ! py=$(python_bin); then
    return 1
  fi
  "$py" -m pip install --user pipenv
}

install_poetry() {
  local py
  if ! py=$(python_bin); then
    return 1
  fi
  "$py" -m pip install --user poetry
}

install_uv() {
  local py
  if ! py=$(python_bin); then
    return 1
  fi
  "$py" -m pip install --user uv
}

install_conda() {
  local installer="$HOME/miniconda_installer.sh"
  rm -f "$installer"
  if command_exists curl; then
    curl -fsSLo "$installer" https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh
  elif command_exists wget; then
    wget -O "$installer" https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh
  else
    return 1
  fi
  bash "$installer" -b -p "$HOME/miniconda"
  log_info "Conda installed. Add $HOME/miniconda/bin to your PATH."
}

install_go() {
  if install_with_package_manager "golang"; then
    return 0
  fi
  if command_exists brew; then
    brew install go
    return 0
  fi
  return 1
}

install_dotnet() {
  if install_with_package_manager "dotnet-sdk-8.0"; then
    return 0
  fi
  if command_exists brew; then
    brew install --cask dotnet-sdk
    return 0
  fi
  return 1
}

install_cargo() {
  if install_with_package_manager "rustup"; then
    return 0
  fi
  if command_exists brew; then
    brew install rustup-init
    rustup-init -y
    return 0
  fi
  if command_exists curl; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    return 0
  fi
  if command_exists wget; then
    wget https://sh.rustup.rs -O - | sh -s -- -y
    return 0
  fi
  return 1
}

install_composer() {
  if install_with_package_manager "composer"; then
    return 0
  fi
  if command_exists brew; then
    brew install composer
    return 0
  fi
  return 1
}

install_ruby() {
  if install_with_package_manager "ruby-full"; then
    return 0
  fi
  if command_exists brew; then
    brew install ruby
    return 0
  fi
  return 1
}

install_syft() {
  if command_exists curl; then
    curl -sSfL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin
    return 0
  fi
  if command_exists wget; then
    wget -qO- https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin
    return 0
  fi
  return 1
}

install_cyclonedx_py() {
  local py
  if ! py=$(python_bin); then
    return 1
  fi
  "$py" -m pip install --user cyclonedx-bom
}

install_cyclonedx_ruby() {
  if ! command_exists gem; then
    return 1
  fi
  gem install cyclonedx-ruby
}

install_cargo_cyclonedx() {
  if ! command_exists cargo; then
    return 1
  fi
  cargo install cargo-cyclonedx
}

install_dotnet_cyclonedx_tool() {
  if ! command_exists dotnet; then
    return 1
  fi
  dotnet tool install --global CycloneDX
}

install_php_cyclonedx_plugin() {
  if ! command_exists composer; then
    return 1
  fi
  composer global require cyclonedx/cyclonedx-php-composer
}

is_composer_plugin_installed() {
  if ! command_exists composer; then
    return 1
  fi
  composer global show cyclonedx/cyclonedx-php-composer >/dev/null 2>&1
}

is_tool_installed() {
  local key="$1"
  case "$key" in
    python) is_python_available ;;
    maven) command_exists mvn ;;
    gradle) command_exists gradle ;;
    npm) command_exists npm ;;
    yarn) command_exists yarn ;;
    pnpm) command_exists pnpm ;;
    pipenv) command_exists pipenv ;;
    poetry) command_exists poetry ;;
    uv) command_exists uv ;;
    conda) command_exists conda ;;
    go) command_exists go ;;
    dotnet) command_exists dotnet ;;
    cargo) command_exists cargo ;;
    composer) command_exists composer ;;
    gem) command_exists gem ;;
    syft) command_exists syft ;;
    cyclonedx-py) command_exists cyclonedx-py ;;
    cyclonedx-ruby) command_exists cyclonedx-ruby ;;
    cargo-cyclonedx) command_exists cargo-cyclonedx ;;
    dotnet-cyclonedx) command_exists CycloneDX ;;
    php-cyclonedx-plugin) is_composer_plugin_installed ;;
    *) return 1 ;;
  esac
}

ensure_tool() {
  local key="$1"
  local display="$2"
  if is_tool_installed "$key"; then
    log_info "$display already available."
    return
  fi
  log_info "$display is missing, attempting to install..."
  local installer="install_${key//-/_}"
  if type "$installer" >/dev/null 2>&1; then
    if "$installer"; then
      log_info "$display installed."
      return
    fi
  fi
  log_warn "Unable to install $display automatically; please install it manually."
}

main() {
  local tools=(
    "python:Python 3"
    "maven:Apache Maven"
    "gradle:Gradle"
    "npm:npm (Node.js LTS)"
    "yarn:Yarn"
    "pnpm:pnpm"
    "pipenv:pipenv"
    "poetry:poetry"
    "uv:uv"
    "conda:Conda (Miniconda/Anaconda)"
    "go:Go"
    "dotnet:.NET SDK"
    "cargo:Cargo & Rust toolchain"
    "composer:Composer"
    "gem:Ruby gem"
    "syft:Syft"
    "cyclonedx-py:cyclonedx-py (CycloneDX Python)"
    "cyclonedx-ruby:cyclonedx-ruby"
    "cargo-cyclonedx:cargo-cyclonedx"
    "dotnet-cyclonedx:CycloneDX .NET tool"
    "php-cyclonedx-plugin:CycloneDX PHP Composer plugin"
  )

  for entry in "${tools[@]}"; do
    IFS=':' read -r key display <<< "$entry"
    ensure_tool "$key" "$display"
  done
  log_info "Toolchain check complete."
}

main "$@"
