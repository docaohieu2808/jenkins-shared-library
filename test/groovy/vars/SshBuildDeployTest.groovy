package vars

import static org.junit.jupiter.api.Assertions.*

import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Runs the real generated ssh-build command in bash. `ssh` and `sudo` are replaced by small scripts
 * that run the remote command locally, so the quoting, backup rotation and rollback are exercised
 * for real, not just string-matched.
 */
class SshBuildDeployTest extends StageTestBase {

    File sandbox
    File appDir
    File binDir
    File sudoLog
    File failFlag

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        sandbox = Files.createTempDirectory('ssh-build-').toFile()
        binDir = new File(sandbox, 'bin')
        binDir.mkdirs()
        sudoLog = new File(sandbox, 'sudo.log')
        failFlag = new File(sandbox, 'fail-next-restart')

        script(binDir, 'ssh', '''\
            #!/usr/bin/env bash
            # Fake ssh: drop the options and the host, run the rest locally (stdin carries the script).
            while [ "$1" = "-o" ]; do shift 2; done
            shift
            exec "$@"
            ''')
        script(binDir, 'sudo', """\
            #!/usr/bin/env bash
            echo "sudo \$*" >> '${sudoLog}'
            if [ "\$1" = systemctl ] && [ "\$2" = restart ] && [ -f '${failFlag}' ]; then rm -f '${failFlag}'; exit 1; fi
            """)

        // A bare "origin" and the clone the deploy works in, like a server with the app checked out.
        def origin = new File(sandbox, 'origin.git')
        run(sandbox, 'git', 'init', '-q', '--bare', '-b', 'main', origin.path)
        def seed = new File(sandbox, 'seed')
        run(sandbox, 'git', 'clone', '-q', origin.path, seed.path)
        new File(seed, 'README').text = 'app'
        run(seed, 'git', 'add', '-A')
        run(seed, 'git', '-c', 'user.name=t', '-c', 'user.email=t@example.com', 'commit', '-q', '-m', 'init')
        run(seed, 'git', 'push', '-q', 'origin', 'HEAD:main')
        appDir = new File(sandbox, 'app')
        run(sandbox, 'git', 'clone', '-q', '-b', 'main', origin.path, appDir.path)
    }

    @AfterEach
    void tearDown() {
        sandbox?.deleteDir()
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void script(File dir, String name, String body) {
        def f = new File(dir, name)
        f.text = body.stripIndent()
        f.setExecutable(true)
    }

    private static String run(File dir, String... cmd) {
        def p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        def out = p.inputStream.text
        assertEquals(0, p.waitFor(), "${cmd.join(' ')} failed:\n${out}")
        return out
    }

    @Override
    def runShell(String command) {
        def pb = new ProcessBuilder('bash', '-c', command).directory(sandbox).redirectErrorStream(true)
        pb.environment().put('PATH', binDir.path + File.pathSeparator + System.getenv('PATH'))
        def p = pb.start()
        def out = p.inputStream.text
        def code = p.waitFor()
        if (code != 0) { throw new RuntimeException("sh exited ${code}:\n${out}") }
        return out
    }

    private Map deployConfig(Map extra = [:]) {
        def deploy = [target: 'ssh-build', path: appDir.path, branch: 'main', service: 'site', distDir: 'dist',
                      buildCommand: 'mkdir -p dist && date +%s%N > dist/index.html',
                      hosts: [production: 'deployer@192.0.2.10']]
        deploy.putAll(extra)
        return config(deploy: deploy)
    }

    private void deploy(Map extra = [:]) {
        stage('deployStage').call(deployConfig(extra), [env: 'production'])
    }

    private List<File> backups() {
        return (appDir.listFiles() as List).findAll { it.name.startsWith('dist_backup_') }
    }

    private String served() { new File(appDir, 'dist/index.html').text }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    void firstDeploySwapsInTheBuildAndRestartsTheService() {
        deploy()
        assertTrue(new File(appDir, 'dist').toPath().toFile().exists())
        assertTrue(Files.isSymbolicLink(new File(appDir, 'dist').toPath()), 'dist is a symlink to dist_current')
        assertTrue(new File(appDir, 'dist_current/index.html').exists())
        assertTrue(sudoLog.text.contains('sudo systemctl restart site'))
        assertTrue(backups().isEmpty())
    }

    @Test
    void backupNameIsExpandedOnTheServerNotKeptLiteral() {
        deploy()
        deploy()
        assertEquals(1, backups().size())
        def name = backups()[0].name
        assertTrue(name ==~ /dist_backup_\d{8}_\d{6}_\d+/, "backup name was '${name}'")
        assertFalse(name.contains('$('), name)
    }

    @Test
    void backupRotationKeepsTheConfiguredNumber() {
        4.times { deploy() }
        assertEquals(2, backups().size())
        3.times { deploy(backupCount: 3) }
        assertEquals(3, backups().size())
    }

    @Test
    void failedRestartRollsBackToThePreviousBuild() {
        deploy()
        deploy()
        def previous = served()
        failFlag.text = 'x'
        def e = assertThrows(RuntimeException, { deploy() })
        assertTrue(e.message.contains('rolling back'), e.message)
        assertTrue(e.message.contains('Rollback complete'), e.message)
        assertEquals(previous, served(), 'the previous build is served again')
        assertEquals(4, sudoLog.readLines().findAll { it.contains('restart') }.size(), sudoLog.text)
    }

    @Test
    void envVarValuesWithQuotesAndDollarsArriveLiteral() {
        deploy(envVars: [GREETING: "it's \$HOME \"quoted\""],
               buildCommand: 'mkdir -p dist && printf "%s" "$GREETING" > dist/index.html')
        assertEquals("it's \$HOME \"quoted\"", served())
    }

    @Test
    void envVarNameThatIsNotAnIdentifierIsRejected() {
        assertThrows(IllegalStateException, { deploy(envVars: ['A=1; touch pwned #': 'x']) })
        assertFalse(new File(appDir, 'pwned').exists())
    }

    @Test
    void failingVerifyCommandAbortsBeforeTheSwap() {
        deploy()
        def before = served()
        def e = assertThrows(RuntimeException, { deploy(verifyCommand: 'false') })
        assertTrue(e.message.contains('BUILD VERIFICATION FAILED'), e.message)
        assertEquals(before, served(), 'the live build is still served')
        assertTrue(Files.isSymbolicLink(new File(appDir, 'dist').toPath()), 'dist points at the live build again')
    }

    @Test
    void shallowCloneUsesFetchDepth() {
        deploy(gitDepth: 1)
        assertTrue(new File(appDir, 'dist_current/index.html').exists())
    }
}
