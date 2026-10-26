package vars

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class SecurityAndSonarTest extends StageTestBase {

    @Test
    void auditFailsOnHighByDefaultAndNeverSwallowsTheExitCode() {
        stage('securityScan').call(config())
        assertEquals(['npm audit --audit-level=high'], shellCommands)
    }

    @Test
    void failOnLevelMapsToEachTool() {
        stage('securityScan').call(config(securityFailOn: 'critical'))
        stage('securityScan').call(config(language: 'java', buildTool: 'maven', securityFailOn: 'MEDIUM'))
        assertTrue(allCommands().contains('npm audit --audit-level=critical'))
        assertTrue(allCommands().contains('-DfailBuildOnCVSS=4'))
        assertFalse(allCommands().contains('|| true'))
    }

    @Test
    void imageScanRunsOnTheBuiltImageWithAPinnedTrivy() {
        binding.getVariable('env').BUILT_IMAGE = 'registry.example/app:main-7'
        stage('securityScan').call(config(docker: true, securityFailOn: 'HIGH'))
        def trivy = shellCommands.find { it.contains('trivy') }
        assertNotNull(trivy, allCommands())
        assertTrue(trivy.contains('aquasec/trivy:0.76.1'), trivy)
        assertTrue(trivy.contains('--severity HIGH,CRITICAL --exit-code 1'), trivy)
        assertTrue(trivy.contains('registry.example/app:main-7'), trivy)
        assertFalse(trivy.contains('|| true'), trivy)
    }

    @Test
    void imageScanIsSkippedWithoutABuiltImage() {
        stage('securityScan').call(config(docker: true))
        assertFalse(allCommands().contains('trivy'))
    }

    @Test
    void failOnNoneReportsFindingsAsUnstable() {
        helper.registerAllowedMethod('sh', [String], { String c -> throw new RuntimeException('vulnerabilities found') })
        stage('securityScan').call(config(securityFailOn: 'NONE'))
        assertEquals('UNSTABLE', binding.getVariable('currentBuild').result)
    }

    @Test
    void failingScanFailsTheStageByDefault() {
        helper.registerAllowedMethod('sh', [String], { String c -> throw new RuntimeException('vulnerabilities found') })
        assertThrows(RuntimeException, { stage('securityScan').call(config()) })
    }

    @Test
    void unknownLevelIsRejected() {
        def e = assertThrows(IllegalStateException, { stage('securityScan').call(config(securityFailOn: 'SEVERE')) })
        assertTrue(e.message.contains('securityFailOn'))
    }

    @Test
    void sonarTrueDoesNotCrashAndUsesTheTokenProperty() {
        helper.registerAllowedMethod('withSonarQubeEnv', [String, Closure], { String server, Closure c -> c.call() })
        stage('sonarScan').call(config(sonar: true))
        def cmd = allCommands()
        assertTrue(cmd.contains("-Dsonar.projectKey='my-app'"), cmd)
        assertTrue(cmd.contains('-Dsonar.token="$SONAR_AUTH_TOKEN"'), cmd)
        assertFalse(cmd.contains('sonar.login'), cmd)
    }

    @Test
    void sonarMapSetsProjectKeyAndServer() {
        String usedServer = null
        helper.registerAllowedMethod('withSonarQubeEnv', [String, Closure], { String server, Closure c -> usedServer = server; c.call() })
        stage('sonarScan').call(config(sonar: [projectKey: 'team_app', server: 'sonar-prod']))
        assertEquals('sonar-prod', usedServer)
        assertTrue(allCommands().contains("-Dsonar.projectKey='team_app'"))
    }
}
