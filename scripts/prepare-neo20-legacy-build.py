"""Keep NeoGradle's 1.20.2/1.20.3 adapter build aligned with the shared Neo20 build."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'neo20/build.gradle').read_text(encoding='utf-8')
source = source.replace("id 'net.neoforged.moddev' version '2.0.148'", "id 'net.neoforged.gradle.userdev' version '7.0.116'")
start = source.index('neoForge {')
end = source.index('repositories {', start)
source = source[:start] + '''runs {
    configureEach { modSource sourceSets.main }
    client { workingDirectory = file("../run-neoforge-${mcVersion}") }
}
''' + source[end:]
source = source.replace("dependencies {\n", 'dependencies {\n    implementation "net.neoforged:neoforge:${neoVersion}"\n', 1)
source = source.replace("tasks.named('processResources') {", """tasks.named('processResources') {
    inputs.property 'dependencySchema', 'fml1-mandatory'
    // FML 1.0.16 (NeoForge 1.20.2/1.20.3) predates the dependency type field.
    filesMatching('META-INF/mods.toml') {
        filter { line -> line.replace('type="required"', 'mandatory=true') }
    }""")
source = source.replace("tasks.named('test') { useJUnitPlatform() }", """tasks.named('test') { useJUnitPlatform() }

// Match the other native modules: the unclassified artifact includes nested runtime dependencies.
tasks.named('jar') { archiveClassifier = 'slim' }
tasks.named('jarJar') { archiveClassifier = '' }
tasks.named('build') { dependsOn 'jarJar' }""")
(root / 'neo20/legacy.gradle').write_text(source, encoding='utf-8', newline='\n')
