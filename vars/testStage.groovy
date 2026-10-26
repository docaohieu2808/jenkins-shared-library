// Unit tests fail the build. Lint failures mark the build UNSTABLE (visible, not fatal).
def call(Map config, Map args = [:]) {
    def type = args.type ?: 'unit'
    echo "Running ${type} tests for ${config.language}..."

    if (type == 'lint') {
        catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
            lint(config)
        }
    } else {
        unit(config)
    }
}

def unit(Map config) {
    switch (config.language) {
        case 'node':
            def cmd = [yarn: 'yarn test', pnpm: 'pnpm test --if-present', bun: 'bun test'][config.buildTool] ?: 'npm test --if-present'
            sh cmd
            break

        case 'java':
            sh(config.buildTool == 'gradle' ? './gradlew test' : 'mvn test -B')
            break

        case 'python':
            sh 'python -m pytest -q'
            break

        case 'go':
            sh 'go test -cover -race ./...'
            break

        case 'dotnet':
            sh 'dotnet test --no-build'
            break

        case 'php':
            sh 'if [ -x vendor/bin/phpunit ]; then vendor/bin/phpunit; else php artisan test; fi'
            break

        case 'rust':
            sh 'cargo test'
            break

        default:
            echo "Unknown language '${config.language}' - skipping unit tests"
    }
}

def lint(Map config) {
    switch (config.language) {
        case 'node':
            def cmd = [yarn: 'yarn', pnpm: 'pnpm', bun: 'bun'][config.buildTool] ?: 'npm'
            sh "${cmd} run lint --if-present"
            break

        case 'java':
            sh(config.buildTool == 'gradle' ? './gradlew checkstyleMain' : 'mvn checkstyle:check -B')
            break

        case 'python':
            sh 'if command -v ruff >/dev/null; then ruff check .; else flake8 .; fi'
            break

        case 'go':
            sh 'golangci-lint run'
            break

        case 'php':
            sh 'if [ -x vendor/bin/phpcs ]; then vendor/bin/phpcs; else vendor/bin/pint --test; fi'
            break

        case 'rust':
            sh 'cargo clippy -- -D warnings'
            break

        default:
            echo "No linter configured for '${config.language}' - skipping"
    }
}
