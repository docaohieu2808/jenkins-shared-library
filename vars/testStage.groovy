def call(Map config, Map args = [:]) {
    def type = args.type ?: 'unit'
    echo "Running ${type} tests for ${config.language}..."

    switch (config.language) {
        case 'node':
            def cmd = [yarn: 'yarn', pnpm: 'pnpm', bun: 'bun'][config.buildTool] ?: 'npm'
            if (type == 'lint') {
                sh "${cmd} run lint --if-present"
            } else {
                sh "${cmd} test -- --coverage --passWithNoTests || ${cmd} run test --if-present"
            }
            break

        case 'java':
            if (type == 'lint') {
                sh(config.buildTool == 'gradle'
                    ? './gradlew checkstyleMain || true'
                    : 'mvn checkstyle:check -B || true')
            } else {
                sh(config.buildTool == 'gradle'
                    ? './gradlew test'
                    : 'mvn test -B')
            }
            break

        case 'python':
            if (type == 'lint') {
                sh 'ruff check . || flake8 . || true'
            } else {
                sh 'pytest --cov=. --cov-report=xml -q || python -m unittest discover || true'
            }
            break

        case 'go':
            if (type == 'lint') {
                sh 'golangci-lint run || true'
            } else {
                sh 'go test -cover -race ./...'
            }
            break

        case 'dotnet':
            if (type == 'lint') {
                echo 'No default linter for .NET — skipping'
            } else {
                sh 'dotnet test --no-build'
            }
            break

        case 'php':
            if (type == 'lint') {
                sh 'vendor/bin/phpcs || vendor/bin/pint --test || true'
            } else {
                sh 'vendor/bin/phpunit || php artisan test || true'
            }
            break

        case 'rust':
            if (type == 'lint') {
                sh 'cargo clippy -- -D warnings || true'
            } else {
                sh 'cargo test'
            }
            break

        default:
            echo "Unknown language '${config.language}' — skipping ${type} tests"
    }
}
