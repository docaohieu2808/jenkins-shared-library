package org.pipeline

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class ConfigLoaderTest {

    /** Minimal stand-in for the pipeline `steps` object. */
    static class FakeSteps implements Serializable {
        Set<String> files = [] as Set
        Map<String, Object> yaml = [:]
        boolean fileExists(String name) { files.contains(name) }
        Object readYaml(Map args) { yaml[args.file] }
    }

    private Map load(FakeSteps steps, Map detected = [:], Map overrides = [:]) {
        return new ConfigLoader(steps).load(detected, overrides)
    }

    @Test
    void zeroConfigDefaultsDoNotRequireADeployTarget() {
        def config = load(new FakeSteps(), [language: 'node', hasDocker: false])
        assertNull(config.deploy, 'no deploy target unless the project asks for one')
        assertEquals([:], config.notifications)
        assertEquals('HIGH', config.securityFailOn)
        assertEquals(false, config.sonar)
        assertEquals(false, config.docker)
    }

    @Test
    void dockerFollowsTheDockerfileUnlessSetExplicitly() {
        assertEquals(true, load(new FakeSteps(), [hasDocker: true]).docker)
        assertEquals(false, load(new FakeSteps(), [hasDocker: true], [docker: false]).docker)
    }

    @Test
    void layersApplyInOrderDefaultsDetectedProjectOverrides() {
        def steps = new FakeSteps(files: ['.ci.yml'] as Set,
                yaml: ['.ci.yml': [timeout: 10, deploy: [target: 'ssh', hosts: [staging: 'a@h1']]]])
        def config = load(steps, [language: 'go', timeout: 5], [deploy: [hosts: [staging: 'b@h2']]])
        assertEquals(10, config.timeout, '.ci.yml beats detected and defaults')
        assertEquals('ssh', config.deploy.target, 'maps merge instead of being replaced')
        assertEquals('b@h2', config.deploy.hosts.staging, 'Jenkinsfile overrides beat .ci.yml')
    }

    @Test
    void nullOverrideDoesNotEraseAValue() {
        def config = load(new FakeSteps(), [:], [timeout: null])
        assertEquals(30, config.timeout)
    }

    @Test
    void sonarCanBeABooleanOrAMap() {
        assertEquals(true, load(new FakeSteps(), [:], [sonar: true]).sonar)
        assertEquals('x', load(new FakeSteps(), [:], [sonar: [projectKey: 'x']]).sonar.projectKey)
    }
}
