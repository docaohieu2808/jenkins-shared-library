package vars

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class DeployStageTest extends StageTestBase {

    @Test
    void noDeployTargetSkipsTheStageInsteadOfFailing() {
        stage('deployStage').call(config(deploy: null), [env: 'staging'])
        assertTrue(shellCommands.isEmpty())
    }

    @Test
    void unknownTargetIsAnError() {
        def e = assertThrows(IllegalStateException, { stage('deployStage').call(config(deploy: [target: 'nope']), [env: 'staging']) })
        assertTrue(e.message.contains('Unknown deploy target'))
    }

    @Test
    void dockerComposeSendsTheScriptThroughAQuotedHeredoc() {
        stage('deployStage').call(config(deploy: [target: 'docker-compose', hosts: [staging: 'deployer@192.0.2.5'], path: '/opt/app']),
                [env: 'staging'])
        def cmd = allCommands()
        assertTrue(cmd.startsWith('ssh -o StrictHostKeyChecking=accept-new deployer@192.0.2.5 bash -s <<\'REMOTE_SCRIPT\''), cmd)
        assertTrue(cmd.contains('cd /opt/app'), cmd)
        assertTrue(cmd.contains('docker compose up -d --remove-orphans'), cmd)
    }

    @Test
    void missingHostIsReported() {
        def e = assertThrows(IllegalStateException, { stage('deployStage').call(config(deploy: [target: 'ssh']), [env: 'staging']) })
        assertTrue(e.message.contains('deploy.hosts.staging'))
    }

    @Test
    void hostWithShellMetacharactersIsRejected() {
        ['host; rm -rf /', 'a@b$(id)', 'a b'].each { bad ->
            assertThrows(IllegalStateException, {
                stage('deployStage').call(config(deploy: [target: 'ssh', command: 'true', hosts: [staging: bad]]), [env: 'staging'])
            })
        }
        assertTrue(shellCommands.isEmpty())
    }

    @Test
    void remoteCommandWithQuotesIsDeliveredIntact() {
        stage('deployStage').call(config(deploy: [target: 'ssh', hosts: [staging: 'u@192.0.2.5'],
                                                  command: "echo 'it works' && echo \"two\""]), [env: 'staging'])
        assertTrue(allCommands().contains("echo 'it works' && echo \"two\""), allCommands())
    }

    @Test
    void k8sNoLongerUsesTheDeprecatedRecordFlag() {
        binding.getVariable('env').BUILT_IMAGE = 'app:1'
        stage('deployStage').call(config(deploy: [target: 'k8s']), [env: 'staging'])
        assertFalse(allCommands().contains('--record'), allCommands())
        assertTrue(allCommands().contains('rollout status'))
    }

    @Test
    void cloudRunIsPrivateUnlessMadePublicOnPurpose() {
        binding.getVariable('env').BUILT_IMAGE = 'app:1'
        stage('deployStage').call(config(deploy: [target: 'cloud-run', project: 'p']), [env: 'staging'])
        assertTrue(allCommands().contains('--no-allow-unauthenticated'), allCommands())
        shellCommands.clear()
        stage('deployStage').call(config(deploy: [target: 'cloud-run', project: 'p', allowUnauthenticated: true]), [env: 'staging'])
        assertFalse(allCommands().contains('--no-allow-unauthenticated'), allCommands())
        assertTrue(allCommands().contains('--allow-unauthenticated'), allCommands())
    }
}
