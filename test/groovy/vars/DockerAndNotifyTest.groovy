package vars

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class DockerAndNotifyTest extends StageTestBase {

    @Test
    void buildOnlyBuildsAndExportsTheImage() {
        binding.getVariable('env').BRANCH_NAME = 'main'
        stage('dockerBuildPush').call(config(deploy: [registry: 'registry.example']), [action: 'build'])
        assertEquals(['docker build -t registry.example/my-app:main-7 .'], shellCommands)
        assertEquals('registry.example/my-app:main-7', binding.getVariable('env').BUILT_IMAGE)
    }

    @Test
    void pushOnlyPushesAndTagsLatestOnMain() {
        binding.getVariable('env').BRANCH_NAME = 'main'
        stage('dockerBuildPush').call(config(deploy: [registry: 'registry.example']), [action: 'push'])
        def cmd = allCommands()
        assertFalse(cmd.contains('docker build'), cmd)
        assertTrue(cmd.contains('docker push registry.example/my-app:main-7'), cmd)
        assertTrue(cmd.contains('docker push registry.example/my-app:latest'), cmd)
    }

    @Test
    void withoutARegistryNothingIsPushed() {
        stage('dockerBuildPush').call(config())
        assertEquals(1, shellCommands.size())
        assertTrue(shellCommands[0].startsWith('docker build'))
    }

    @Test
    void webhookPayloadGoesThroughAFileSoQuotesCannotBreakTheCommand() {
        binding.getVariable('env').JOB_NAME = "team/it's \"quoted\""
        stage('notifyStage').call(config(notifications: [webhook: 'https://hooks.example/x']), [status: 'FAILURE'])
        assertTrue(writtenFiles['notify-payload.json'].contains('FAILURE'))
        def cmd = allCommands()
        assertTrue(cmd.contains('-d @notify-payload.json "$NOTIFY_WEBHOOK"'), cmd)
        assertFalse(cmd.contains("it's"), cmd)
        assertFalse(cmd.contains('|| true'), cmd)
    }

    @Test
    void noNotificationsConfiguredRunsNothing() {
        stage('notifyStage').call(config(notifications: [:]), [status: 'SUCCESS'])
        assertTrue(shellCommands.isEmpty())
    }
}
