package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach

/**
 * Loads the scripts in vars/ the way Jenkins does and records the shell commands they run.
 * Subclasses read {@code shellCommands} to assert on what a stage would execute.
 */
abstract class StageTestBase extends BasePipelineTest {

    List<String> shellCommands = []
    Set<String> existingFiles = [] as Set
    Map<String, String> writtenFiles = [:]

    @Override
    @BeforeEach
    void setUp() throws Exception {
        scriptExtension = 'groovy'
        super.setUp()
        shellCommands.clear()
        existingFiles.clear()
        writtenFiles.clear()

        binding.getVariable('env').putAll([JOB_BASE_NAME: 'my-app', JOB_NAME: 'team/my-app',
                                           BUILD_NUMBER: '7', BUILD_URL: 'http://jenkins/job/my-app/7/'])

        helper.registerAllowedMethod('sh', [String], { String cmd -> runShell(cmd) })
        helper.registerAllowedMethod('sh', [GString], { GString cmd -> runShell(cmd.toString()) })
        helper.registerAllowedMethod('sh', [Map], { Map m -> runShell(m.script.toString()) })
        // The default mock only records the failure; the real step aborts the stage.
        helper.registerAllowedMethod('error', [String], { String msg -> throw new IllegalStateException(msg) })
        helper.registerAllowedMethod('error', [GString], { GString msg -> throw new IllegalStateException(msg.toString()) })
        helper.registerAllowedMethod('fileExists', [String], { String f -> existingFiles.contains(f) })
        helper.registerAllowedMethod('fileExists', [Map], { Map m -> existingFiles.contains(m.file) })
        helper.registerAllowedMethod('writeFile', [Map], { Map m -> writtenFiles[m.file.toString()] = m.text.toString() })
        helper.registerAllowedMethod('withEnv', [List, Closure], { List vars, Closure body ->
            vars.each { String kv -> binding.getVariable('env')[kv.substring(0, kv.indexOf('='))] = kv.substring(kv.indexOf('=') + 1) }
            body.call()
        })
    }

    /** Overridden by tests that execute the command for real. */
    def runShell(String command) {
        shellCommands << command
        return 0
    }

    def stage(String name) {
        return loadScript("vars/${name}.groovy")
    }

    Map config(Map extra = [:]) {
        def base = [language: 'node', framework: 'unknown', buildTool: 'npm', docker: false,
                    security: true, securityFailOn: 'HIGH', lint: true, sonar: false, deploy: null]
        base.putAll(extra)
        return base
    }

    String allCommands() { shellCommands.join('\n') }
}
