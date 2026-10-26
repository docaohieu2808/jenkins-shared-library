package vars

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class BuildAndTestStageTest extends StageTestBase {

    @Test
    void nodeBuildUsesTheLockfile() {
        stage('buildStage').call(config())
        assertEquals(['npm ci && npm run build --if-present'], shellCommands)
    }

    @Test
    void pythonBuildFollowsTheDetectedTool() {
        stage('buildStage').call(config(language: 'python', buildTool: 'poetry'))
        stage('buildStage').call(config(language: 'python', buildTool: 'uv'))
        stage('buildStage').call(config(language: 'python', buildTool: 'pip'))
        existingFiles << 'requirements.txt'
        stage('buildStage').call(config(language: 'python', buildTool: 'pip'))
        assertEquals(['poetry install --no-interaction', 'uv sync --frozen', 'pip install .',
                      'pip install -r requirements.txt'], shellCommands)
    }

    @Test
    void unitTestFailuresAreNotHidden() {
        ['node', 'java', 'python', 'go', 'php', 'rust'].each { lang ->
            stage('testStage').call(config(language: lang, buildTool: lang == 'java' ? 'maven' : 'npm'), [type: 'unit'])
        }
        assertFalse(allCommands().contains('|| true'), allCommands())
        assertFalse(allCommands().contains('|| echo'), allCommands())
        assertTrue(allCommands().contains('npm test --if-present'))
        assertTrue(allCommands().contains('python -m pytest -q'))
        assertTrue(allCommands().contains('mvn test -B'))
    }

    @Test
    void nodeTestDoesNotPassJestFlagsToEveryRunner() {
        stage('testStage').call(config(buildTool: 'yarn'), [type: 'unit'])
        assertEquals(['yarn test'], shellCommands)
    }

    @Test
    void lintRunsTheRealToolAndAFailureMakesTheBuildUnstable() {
        stage('testStage').call(config(language: 'go'), [type: 'lint'])
        assertEquals(['golangci-lint run'], shellCommands)
        assertFalse(allCommands().contains('|| true'))
    }

    @Test
    void lintFailureIsReportedAsUnstableNotSwallowed() {
        helper.registerAllowedMethod('sh', [String], { String c -> throw new RuntimeException('lint failed') })
        stage('testStage').call(config(language: 'go'), [type: 'lint'])
        assertEquals('UNSTABLE', binding.getVariable('currentBuild').result)
    }

    @Test
    void unitFailureStopsTheStage() {
        helper.registerAllowedMethod('sh', [String], { String c -> throw new RuntimeException('tests failed') })
        assertThrows(RuntimeException, { stage('testStage').call(config(), [type: 'unit']) })
    }
}
