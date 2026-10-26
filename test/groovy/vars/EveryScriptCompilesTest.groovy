package vars

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

/** Catches syntax errors in the scripts no other unit test loads (autoPipeline is declarative). */
class EveryScriptCompilesTest {

    @Test
    void everyVarsScriptCompiles() {
        def scripts = new File('vars').listFiles().findAll { it.name.endsWith('.groovy') }
        assertTrue(scripts.size() >= 8, "found ${scripts*.name}")
        scripts.each { f ->
            try {
                new GroovyShell().parse(f)
            } catch (Throwable t) {
                fail("${f.name} does not compile: ${t.message}")
            }
        }
    }
}
