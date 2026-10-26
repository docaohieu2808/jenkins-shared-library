package org.pipeline

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class ProjectDetectorTest {

    static class FakeSteps implements Serializable {
        Map<String, String> files = [:]
        boolean fileExists(String name) { files.containsKey(name) }
        String readFile(String name) { files[name] }
        Object readJSON(Map args) { new groovy.json.JsonSlurper().parseText(files[args.file]) }
        int sh(Map args) { 1 }
    }

    private Map detect(Map<String, String> files) {
        return new ProjectDetector(new FakeSteps(files: files)).detect()
    }

    @Test
    void detectsNodeFrameworkAndPackageManager() {
        def r = detect(['package.json': '{"dependencies":{"express":"5"}}', 'pnpm-lock.yaml': ''])
        assertEquals('node', r.language)
        assertEquals('express', r.framework)
        assertEquals('pnpm', r.buildTool)
    }

    @Test
    void detectsJavaMavenSpringBoot() {
        def r = detect(['pom.xml': '<parent>spring-boot-starter-parent</parent>'])
        assertEquals(['java', 'maven', 'springboot'], [r.language, r.buildTool, r.framework])
    }

    @Test
    void detectsGoAndRust() {
        assertEquals('go', detect(['go.mod': 'module x']).language)
        assertEquals('rust', detect(['Cargo.toml': '[package]']).language)
    }

    @Test
    void pythonToolFollowsTheLockfileNotJustPyproject() {
        assertEquals('poetry', detect(['pyproject.toml': '', 'poetry.lock': '']).buildTool)
        assertEquals('uv', detect(['pyproject.toml': '', 'uv.lock': '']).buildTool)
        assertEquals('pip', detect(['pyproject.toml': '']).buildTool)
        assertEquals('pip', detect(['requirements.txt': '']).buildTool)
        assertEquals('pipenv', detect(['requirements.txt': '', 'Pipfile': '']).buildTool)
    }

    @Test
    void detectsDockerFiles() {
        def r = detect(['go.mod': 'module x', 'Dockerfile': '', 'compose.yml': ''])
        assertTrue(r.hasDocker)
        assertTrue(r.hasCompose)
        assertFalse(detect(['go.mod': 'module x']).hasDocker)
    }

    @Test
    void unknownProjectStaysUnknown() {
        assertEquals('unknown', detect([:]).language)
    }
}
