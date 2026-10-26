package vars

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource
import static org.junit.jupiter.api.Assertions.*

import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Runs the real declarative autoPipeline() (all stages, `when` conditions, the library's own vars/ and
 * src/ classes) and checks what the stage graph does, not what a single stage prints:
 *   - build, then test, then image build, then scan, then push, then deploy;
 *   - a failing step stops every later stage;
 *   - a deploy stage runs only on its branch and only when a deploy target is configured.
 * Only the leaf `sh` step is faked; it records every command in order.
 */
class AutoPipelineOrderingTest extends DeclarativePipelineTest {

    List<String> commands = []
    Set<String> existingFiles = [] as Set
    Closure<Boolean> failWhen = { String cmd -> false }

    @Override
    @BeforeEach
    void setUp() throws Exception {
        scriptExtension = 'groovy'
        super.setUp()
        commands.clear()
        existingFiles.clear()
        failWhen = { String cmd -> false }

        helper.registerSharedLibrary(library().name('auto-pipeline')
                .defaultVersion('master').allowOverride(true).implicit(true)
                .targetPath('<irrelevant>').retriever(projectSource()).build())

        binding.getVariable('env').putAll([JOB_BASE_NAME: 'my-app', JOB_NAME: 'team/my-app',
                                           BUILD_NUMBER: '7', BUILD_URL: 'http://jenkins/job/my-app/7/'])
        binding.setVariable('scm', [:])

        helper.registerAllowedMethod('sh', [String], { String cmd -> runShell(cmd) })
        helper.registerAllowedMethod('sh', [GString], { GString cmd -> runShell(cmd.toString()) })
        helper.registerAllowedMethod('sh', [Map], { Map m -> runShell(m.script.toString()) })
        helper.registerAllowedMethod('error', [String], { String msg -> throw new IllegalStateException(msg) })
        helper.registerAllowedMethod('error', [GString], { GString msg -> throw new IllegalStateException(msg.toString()) })
        helper.registerAllowedMethod('node', [Closure], { Closure body -> body.call() })
        helper.registerAllowedMethod('checkout', [Object], { Object scm -> null })
        helper.registerAllowedMethod('fileExists', [String], { String f -> existingFiles.contains(f) })
        helper.registerAllowedMethod('readJSON', [Map], { Map m -> [:] })
        helper.registerAllowedMethod('readYaml', [Map], { Map m -> [:] })
        helper.registerAllowedMethod('cleanWs', [], { null })
        helper.registerAllowedMethod('timeout', [Map, Closure], { Map m, Closure c -> c.call() })
        helper.registerAllowedMethod('timestamps', [], { null })
        helper.registerAllowedMethod('disableConcurrentBuilds', [], { null })
        helper.registerAllowedMethod('buildDiscarder', [Object], { null })
        helper.registerAllowedMethod('logRotator', [Map], { Map m -> [:] })
        existingFiles << 'package.json'
    }

    def runShell(String cmd) {
        if (failWhen.call(cmd)) {
            throw new RuntimeException("simulated failure: ${cmd}")
        }
        commands << cmd
        return 0
    }

    void runPipeline(Map overrides = [:], String branch = null) {
        if (branch) {
            binding.getVariable('env').BRANCH_NAME = branch
        }
        loadScript('vars/autoPipeline.groovy').call(overrides)
    }

    int indexOf(String fragment) {
        return commands.findIndexOf { it.contains(fragment) }
    }

    @Test
    void buildThenTestThenImageBuildThenScanThenPushThenDeploy() {
        existingFiles << 'Dockerfile'
        runPipeline([deploy: [target: 'ssh', registry: 'registry.example.test',
                              hosts: [staging: 'ci@staging.example.test'], command: 'true']], 'develop')

        def order = ['npm ci', 'npm test', 'docker build', 'aquasec/trivy', 'npm audit', 'docker push',
                     'staging.example.test']
        def positions = order.collect { indexOf(it) }
        assertFalse(positions.contains(-1), "missing step in ${commands}")
        assertEquals(positions.sort(false), positions, "steps ran out of order: ${commands}")
    }

    @Test
    void aFailingTestStopsScanBuildAndDeploy() {
        existingFiles << 'Dockerfile'
        failWhen = { String cmd -> cmd.contains('npm test') }
        assertThrows(Exception) {
            runPipeline([deploy: [target: 'ssh', registry: 'registry.example.test',
                                  hosts: [staging: 'ci@staging.example.test'], command: 'true']], 'develop')
        }
        assertTrue(indexOf('npm ci') >= 0, "build should have run: ${commands}")
        ['docker build', 'npm audit', 'aquasec/trivy', 'docker push', 'staging.example.test'].each {
            assertEquals(-1, indexOf(it), "'${it}' ran after the failing test: ${commands}")
        }
    }

    @Test
    void aFailingBuildStopsTheTests() {
        failWhen = { String cmd -> cmd.contains('npm ci') }
        assertThrows(Exception) { runPipeline() }
        assertEquals(-1, indexOf('npm test'), "tests ran after a failed build: ${commands}")
    }

    @Test
    void aFailingImageScanPreventsThePush() {
        existingFiles << 'Dockerfile'
        failWhen = { String cmd -> cmd.contains('aquasec/trivy') }
        assertThrows(Exception) {
            runPipeline([deploy: [registry: 'registry.example.test']])
        }
        assertTrue(indexOf('docker build') >= 0, "image should have been built: ${commands}")
        assertEquals(-1, indexOf('docker push'), "an unscanned image was pushed: ${commands}")
    }

    @Test
    void stagingDeployRunsOnDevelopOnlyWithItsOwnHost() {
        def deploy = [target: 'ssh', hosts: [staging: 'ci@staging.example.test', production: 'ci@prod.example.test'],
                      command: 'true', autoApprove: true]
        runPipeline([deploy: deploy], 'develop')
        assertTrue(indexOf('ci@staging.example.test') >= 0, "staging deploy missing: ${commands}")
        assertEquals(-1, indexOf('ci@prod.example.test'), "production deployed from develop: ${commands}")
    }

    @Test
    void productionDeployRunsOnMainOnly() {
        def deploy = [target: 'ssh', hosts: [staging: 'ci@staging.example.test', production: 'ci@prod.example.test'],
                      command: 'true', autoApprove: true]
        runPipeline([deploy: deploy], 'main')
        assertTrue(indexOf('ci@prod.example.test') >= 0, "production deploy missing: ${commands}")
        assertEquals(-1, indexOf('ci@staging.example.test'), "staging deployed from main: ${commands}")
    }

    @Test
    void featureBranchesNeverDeploy() {
        def deploy = [target: 'ssh', hosts: [staging: 'ci@staging.example.test', production: 'ci@prod.example.test'],
                      command: 'true', autoApprove: true]
        runPipeline([deploy: deploy], 'feature/login')
        assertEquals(-1, indexOf('ssh '), "deployed from a feature branch: ${commands}")
    }

    @Test
    void noDeployTargetMeansNoDeployEvenOnDevelop() {
        runPipeline([:], 'develop')
        assertEquals(-1, indexOf('ssh '), "deployed without a target: ${commands}")
        assertTrue(indexOf('npm test') >= 0)
    }
}
