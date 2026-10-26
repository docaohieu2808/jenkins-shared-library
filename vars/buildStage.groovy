def call(Map config) {
    echo "Building ${config.language}/${config.framework} with ${config.buildTool}..."

    switch (config.language) {
        case 'node':
            def cmd = [yarn: 'yarn', pnpm: 'pnpm', bun: 'bun'][config.buildTool] ?: 'npm'
            def install = cmd == 'npm' ? 'npm ci' : "${cmd} install"
            sh "${install} && ${cmd} run build --if-present"
            break

        case 'java':
            if (config.buildTool == 'gradle') {
                sh './gradlew build -x test'
            } else {
                sh 'mvn clean package -DskipTests -B'
            }
            break

        case 'python':
            if (config.buildTool == 'poetry') {
                sh 'poetry install --no-interaction'
            } else if (config.buildTool == 'uv') {
                sh 'uv sync --frozen'
            } else if (config.buildTool == 'pipenv') {
                sh 'pipenv install --deploy'
            } else if (fileExists('requirements.txt')) {
                sh 'pip install -r requirements.txt'
            } else {
                sh 'pip install .'
            }
            break

        case 'go':
            sh 'go mod download && go build ./...'
            break

        case 'dotnet':
            sh 'dotnet restore && dotnet build --no-restore'
            break

        case 'php':
            sh 'composer install --no-interaction --prefer-dist --optimize-autoloader'
            if (config.framework == 'laravel') {
                sh 'php artisan config:cache'
            }
            break

        case 'rust':
            sh 'cargo build --release'
            break

        default:
            echo "Unknown language '${config.language}' - skipping build stage"
    }
}
