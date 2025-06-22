package org.javacs;

import com.google.devtools.build.lib.analysis.AnalysisProtos;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class InferConfig {
    private static final Logger LOG = Logger.getLogger("main");

    /** Root of the workspace that is currently open in VSCode */
    private final Path workspaceRoot;

    /** External dependencies specified manually by the user */
    private final Collection<String> externalDependencies;

    /** Location of the maven repository, usually ~/.m2 */
    private final Path mavenHome;

    /** Location of the gradle cache, usually ~/.gradle */
    private final Path gradleHome;

    InferConfig(
            Path workspaceRoot,
            Collection<String> externalDependencies,
            Path mavenHome,
            Path gradleHome) {
        this.workspaceRoot = workspaceRoot;
        this.externalDependencies = externalDependencies;
        this.mavenHome = mavenHome;
        this.gradleHome = gradleHome;
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome());
    }

    InferConfig(Path workspaceRoot) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome());
    }

    private static Path defaultMavenHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2");
    }

    private static Path defaultGradleHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".gradle");
    }

    /**
     * Find .jar files for external dependencies, for examples maven dependencies in ~/.m2 or jars
     * in bazel-genfiles
     */
    Set<Path> classPath() {
        var result = new HashSet<Path>();
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, false);
                if (found == NOT_FOUND) {
                    LOG.warning(
                            String.format(
                                    "Couldn't find jar for %s in %s or %s",
                                    a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            //return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            var deps = mvnDependencies(pomXml, "dependency:list");
            result.addAll(deps);
                LOG.warning("IIIIIIIIIIIIIIIIIIIIIIIIII pom deps size "+deps.size());
        }

        // Gradle
        try {
            createPomFromBuildGradle();
            var pom = workspaceRoot.resolve(".jls/pom.xml");
            LOG.warning("IIIIIIIIIIIIIIIIIIIIIIIIII pom ??? "+pom);
            if (Files.exists(pom)) {
                var deps = mvnDependencies(pom, "dependency:list");
                LOG.warning("IIIIIIIIIIIIIIIIIIIIIIIIII jls depssize "+deps.size());
                result.addAll(deps);
            }
            LOG.warning("IIIIIIIIIIIIIIIIIIIIIIIIII result "+result.size());
        } catch (IOException e) {
            LOG.warning(e.getMessage());
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            result.addAll(bazelClasspath(bazelWorkspaceRoot));
        }

        LOG.warning("total dependencies size: " + result.size()); 
        return result;
    }

    private Path bazelWorkspaceRoot() {
        for (var current = workspaceRoot; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve("WORKSPACE"))) {
                return current;
            }
        }
        return workspaceRoot;
    }

    /** Find source .jar files in local maven repository. */
    Set<Path> buildDocPath() {
        var result = new HashSet<Path>();
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, true);
                if (found == NOT_FOUND) {
                    LOG.warning(
                            String.format(
                                    "Couldn't find doc jar for %s in %s or %s",
                                    a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            //return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            result.addAll(mvnDependencies(pomXml, "dependency:sources"));
        }

        // Gradle
        try {
            createPomFromBuildGradle();
            var pom = workspaceRoot.resolve(".jls/pom.xml");
            if (Files.exists(pom)) {
                result.addAll(mvnDependencies(pom, "dependency:sources"));
            }
        } catch (IOException e) {
            LOG.warning(e.getMessage());
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            result.addAll(bazelSourcepath(bazelWorkspaceRoot));
        }

        LOG.warning("total doc paths size: " + result.size()); 
        return result;
    }

    private Path findAnyJar(Artifact artifact, boolean source) {
        Path maven = findMavenJar(artifact, source);

        if (maven != NOT_FOUND) {
            return maven;
        } else return findGradleJar(artifact, source);
    }

    Path findMavenJar(Artifact artifact, boolean source) {
        var jar =
                mavenHome
                        .resolve("repository")
                        .resolve(artifact.groupId.replace('.', File.separatorChar))
                        .resolve(artifact.artifactId)
                        .resolve(artifact.version)
                        .resolve(fileName(artifact, source));
        if (!Files.exists(jar)) {
            LOG.warning(jar + " does not exist");
            return NOT_FOUND;
        }
        return jar;
    }

    private Path findGradleJar(Artifact artifact, boolean source) {
        // Search for
        // caches/modules-*/files-*/groupId/artifactId/version/*/artifactId-version[-sources].jar
        var base = gradleHome.resolve("caches");
        var pattern =
                "glob:"
                        + String.join(
                                File.separator,
                                base.toString(),
                                "modules-*",
                                "files-*",
                                artifact.groupId,
                                artifact.artifactId,
                                artifact.version,
                                "*",
                                fileName(artifact, source));
        var match = FileSystems.getDefault().getPathMatcher(pattern);

        try {
            return Files.walk(base, 7).filter(match::matches).findFirst().orElse(NOT_FOUND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String fileName(Artifact artifact, boolean source) {
        return artifact.artifactId + '-' + artifact.version + (source ? "-sources" : "") + ".jar";
    }

    static Set<Path> mvnDependencies(Path pomXml, String goal) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        try {
            // TODO consider using mvn valide dependency:copy-dependencies -DoutputDirectory=???
            // instead
            // Run maven as a subprocess
            String[] command = {
                getMvnCommand(),
                "--batch-mode", // Turns off ANSI control sequences
                "validate",
                goal,
                "-DincludeScope=test",
                "-DoutputAbsoluteArtifactFilename=true",
            };
            var output = Files.createTempFile("java-language-server-maven-output", ".txt");
            LOG.info("Running " + String.join(" ", command) + " ...");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workingDirectory)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
                return Set.of();
            }
            // Read output
            var dependencies = new HashSet<Path>();
            for (var line : Files.readAllLines(output)) {
                var jar = readDependency(line);
                if (jar != NOT_FOUND) {
                    dependencies.add(jar);
                }
            }
            return dependencies;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Pattern DEPENDENCY =
            Pattern.compile("^\\[INFO\\]\\s+(.*:.*:.*:.*:.*):(/.*?)( -- module .*)?$");

    static Path readDependency(String line) {
        var match = DEPENDENCY.matcher(line);
        if (!match.matches()) {
            return NOT_FOUND;
        }
        var artifact = match.group(1);
        var path = match.group(2);
        LOG.info(String.format("...%s => %s", artifact, path));
        return Paths.get(path);
    }

    static String getMvnCommand() {
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd");
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat");
            }
        }
        return mvnCommand;
    }

    private static String findExecutableOnPath(String name) {
        for (var dirname : System.getenv("PATH").split(File.pathSeparator)) {
            var file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private boolean buildProtos(Path bazelWorkspaceRoot) {
        var targets = bazelQuery(bazelWorkspaceRoot, "java_proto_library");
        if (targets.size() == 0) {
            return false;
        }
        bazelDryRunBuild(bazelWorkspaceRoot, targets);
        return true;
    }

    private Set<Path> bazelClasspath(Path bazelWorkspaceRoot) {
        var absolute = new HashSet<Path>();

        // Add protos
        if (buildProtos(bazelWorkspaceRoot)) {
            for (var relative :
                    bazelAQuery(bazelWorkspaceRoot, "Javac", "--output", "proto_library")) {
                absolute.add(bazelWorkspaceRoot.resolve(relative));
            }
        }

        // Add rest of classpath
        for (var relative :
                bazelAQuery(
                        bazelWorkspaceRoot,
                        "Javac",
                        "--classpath",
                        "java_library",
                        "java_test",
                        "java_binary")) {
            absolute.add(bazelWorkspaceRoot.resolve(relative));
        }
        return absolute;
    }

    private Set<Path> bazelSourcepath(Path bazelWorkspaceRoot) {
        var absolute = new HashSet<Path>();
        var outputBase = bazelOutputBase(bazelWorkspaceRoot);
        for (var relative :
                bazelAQuery(
                        bazelWorkspaceRoot,
                        "JavaSourceJar",
                        "--sources",
                        "java_library",
                        "java_test",
                        "java_binary")) {
            absolute.add(outputBase.resolve(relative));
        }

        // Add proto source files
        if (buildProtos(bazelWorkspaceRoot)) {
            for (var relative :
                    bazelAQuery(bazelWorkspaceRoot, "Javac", "--source_jars", "proto_library")) {
                absolute.add(bazelWorkspaceRoot.resolve(relative));
            }
        }

        return absolute;
    }

    private Path bazelOutputBase(Path bazelWorkspaceRoot) {
        // Run bazel as a subprocess
        String[] command = {
            "bazel", "info", "output_base",
        };
        var output = fork(bazelWorkspaceRoot, command);
        if (output == NOT_FOUND) {
            return NOT_FOUND;
        }
        // Read output
        try {
            var out = Files.readString(output).trim();
            return Paths.get(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void bazelDryRunBuild(Path bazelWorkspaceRoot, Set<String> targets) {
        var command = new ArrayList<String>();
        command.add("bazel");
        command.add("build");
        command.add("--nobuild");
        command.addAll(targets);
        String[] c = new String[command.size()];
        c = command.toArray(c);
        var output = fork(bazelWorkspaceRoot, c);
        if (output == NOT_FOUND) {
            return;
        }
        return;
    }

    private Set<String> bazelQuery(Path bazelWorkspaceRoot, String filterKind) {
        String[] command = {"bazel", "query", "kind(" + filterKind + ",//...)"};
        var output = fork(bazelWorkspaceRoot, command);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readQueryResult(output);
    }

    private Set<String> readQueryResult(Path output) {
        try {
            Stream<String> stream = Files.lines(output);
            var targets = new HashSet<String>();
            var i = stream.iterator();
            while (i.hasNext()) {
                var t = i.next();
                targets.add(t);
            }
            return targets;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> bazelAQuery(
            Path bazelWorkspaceRoot,
            String filterMnemonic,
            String filterArgument,
            String... kinds) {
        String kindUnion = "";
        for (var kind : kinds) {
            if (kindUnion.length() > 0) {
                kindUnion += " union ";
            }
            kindUnion += "kind(" + kind + ", ...)";
        }
        String[] command = {
            "bazel",
            "aquery",
            "--output=proto",
            "--include_aspects", // required for java_proto_library, see
            // https://stackoverflow.com/questions/63430530/bazel-aquery-returns-no-action-information-for-java-proto-library
            "--allow_analysis_failures",
            "mnemonic(" + filterMnemonic + ", " + kindUnion + ")"
        };
        var output = fork(bazelWorkspaceRoot, command);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readActionGraph(output, filterArgument);
    }

    private Set<String> readActionGraph(Path output, String filterArgument) {
        try {
            var containerV2 =
                    AnalysisProtosV2.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            if (containerV2.getArtifactsCount() != 0
                    && containerV2.getArtifactsList().get(0).getId() != 0) {
                return readActionGraphFromV2(containerV2, filterArgument);
            }
            var containerV1 =
                    AnalysisProtos.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            return readActionGraphFromV1(containerV1, filterArgument);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> readActionGraphFromV1(
            AnalysisProtos.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<String>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (!argumentPaths.contains(artifact.getExecPath())) {
                // artifact was not specified by --filterArgument
                continue;
            }
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative = artifact.getExecPath();
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    private Set<String> readActionGraphFromV2(
            AnalysisProtosV2.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<Integer>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative =
                    buildPath(container.getPathFragmentsList(), artifact.getPathFragmentId());
            if (!argumentPaths.contains(relative)) {
                // artifact was not specified by --filterArgument
                continue;
            }
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    private static String buildPath(List<PathFragment> fragments, int id) {
        for (PathFragment fragment : fragments) {
            if (fragment.getId() == id) {
                if (fragment.getParentId() != 0) {
                    return buildPath(fragments, fragment.getParentId()) + "/" + fragment.getLabel();
                }
                return fragment.getLabel();
            }
        }
        throw new RuntimeException();
    }

    private static Path fork(Path workspaceRoot, String[] command) {
        try {
            LOG.info("Running " + String.join(" ", command) + " ...");
            var output = Files.createTempFile("java-language-server-bazel-output", ".proto");
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workspaceRoot.toFile())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
                return NOT_FOUND;
            }
            return output;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createPomFromBuildGradle() throws IOException {
        Path gradleFilePath = Paths.get("build.gradle");
        Path pomFileName = Paths.get("pom.xml");
        Path pomDirectory = Paths.get(".jls");
        String bootVersion = "";
        boolean addLombok = false;

        List<Dependency> dependencies = new ArrayList<>();
        List<Dependency> platformDependencies = new ArrayList<>();

        List<String> lines = Files.readAllLines(gradleFilePath);

        // Pattern for implementation dependencies
        Pattern implPattern =
                Pattern.compile(
                            "implementation[\\s*,\\(,\\(platform\\(]*['\"](.*?:)(.*?)(:.*?)?['\"]");
        // Pattern for testImplementation dependencies
        Pattern testImplPattern =
                Pattern.compile(
                        "testImplementation[\\s*,\\(,\\(platform\\(]*['\"](.*?:)(.*?)(:.*?)?['\"]");
        // Pattern for spring boot plugin
        Pattern bootPluginPattern = Pattern.compile("id 'org.springframework.boot' version '(.*?)'");

        for (String line : lines) {
            if (line.contains("lombok")) {
                addLombok = true;
            }
            Matcher mm = bootPluginPattern.matcher(line);
            if (mm.find()) {
                bootVersion = mm.group(1); 
            }
            if (line.contains("platform")) {
                Matcher m = implPattern.matcher(line);
                if (m.find()) {
                    platformDependencies.add(new Dependency(m.group(1), m.group(2), m.group(3), false));
                }
                m = testImplPattern.matcher(line);
                if (m.find()) {
                    platformDependencies.add(new Dependency(m.group(1), m.group(2), m.group(3), true));
                }
            } else {
                Matcher m = implPattern.matcher(line);
                if (m.find()) {
                    dependencies.add(new Dependency(m.group(1), m.group(2), m.group(3), false));
                }
                m = testImplPattern.matcher(line);
                if (m.find()) {
                    dependencies.add(new Dependency(m.group(1), m.group(2), m.group(3), true));
                }
            }
        }

        // Generate basic pom.xml
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n");
        pom.append("                             http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("  <modelVersion>4.0.0</modelVersion>\n");
        
        // make parent
        if (!bootVersion.isEmpty()) {
    	    pom.append("  <parent>\n");
	        pom.append("    <groupId>org.springframework.boot</groupId>\n");
		    pom.append("    <artifactId>spring-boot-starter-parent</artifactId>\n");
		    pom.append("    <version>"+bootVersion+"</version>\n");
	        pom.append("  </parent>\n");
        }
    
        // Placeholder project info
        pom.append("  <groupId>com.example</groupId>\n");
        pom.append("  <artifactId>my-app</artifactId>\n");
        pom.append("  <version>1.0-SNAPSHOT</version>\n");

        pom.append("  <dependencyManagement>\n");
        pom.append("    <dependencies>\n");
        
        for (Dependency dep : platformDependencies) {
            pom.append("      <dependency>\n");
            pom.append("        <groupId>")
                    .append(dep.groupId.replace(":", ""))
                    .append("</groupId>\n");
            pom.append("        <artifactId>").append(dep.artifactId).append("</artifactId>\n");
            if (dep.version != null) {
                pom.append("        <version>")
                        .append(dep.version.replace(":", ""))
                        .append("</version>\n");
            }
            pom.append("        <type>pom</type>\n");
            if (dep.isTest) {
                pom.append("        <scope>test</scope>\n");
            }
            pom.append("      </dependency>\n"); 
        }
        pom.append("    </dependencies>\n");
        pom.append("  </dependencyManagement>\n");
        pom.append("  <dependencies>\n");

        if (addLombok) {
            pom.append("      <dependency>\n");
            pom.append("        <groupId>org.projectlombok</groupId>\n");
            pom.append("        <artifactId>lombok</artifactId>\n");
            pom.append("        <version>1.18.24</version>\n");
            pom.append("        <scope>provided</scope>\n");
            pom.append("      </dependency>\n"); 
        }

        for (Dependency dep : dependencies) {
            pom.append("    <dependency>\n");
            pom.append("      <groupId>")
                    .append(dep.groupId.replace(":", ""))
                    .append("</groupId>\n");
            pom.append("      <artifactId>").append(dep.artifactId).append("</artifactId>\n");
            if (dep.version != null) {
                pom.append("      <version>")
                        .append(dep.version.replace(":", ""))
                        .append("</version>\n");
            }
            if (dep.isTest) {
                pom.append("      <scope>test</scope>\n");
            }
            pom.append("    </dependency>\n");
        }

        pom.append("  </dependencies>\n");

        // add annotation processor
        if (addLombok) {
            pom.append("  <build>\n");
	        pom.append("    <plugins>\n");
		    pom.append("      <plugin>\n");
			pom.append("        <groupId>org.apache.maven.plugins</groupId>\n");
			pom.append("        <artifactId>maven-compiler-plugin</artifactId>\n");
			pom.append("        <configuration>\n");
		    pom.append("          <annotationProcessorPaths>\n");
			pom.append("            <path>\n");
			pom.append("              <groupId>org.projectlombok</groupId>\n");
			pom.append("              <artifactId>lombok</artifactId>\n");
			pom.append("              <version>1.18.24</version>\n");
			pom.append("            </path>\n");
			pom.append("          </annotationProcessorPaths>\n");
			pom.append("        </configuration>\n");
		    pom.append("      </plugin>\n");
	        pom.append("    </plugins>\n");
            pom.append("  </build>\n");
        }

        pom.append("</project>\n");

        // Write to pom.xml
        if (!Files.exists(pomDirectory)) {
            Files.createDirectory(pomDirectory);
        }
        Files.write(pomDirectory.resolve(pomFileName), pom.toString().getBytes());

    }

    record Dependency(String groupId, String artifactId, String version, boolean isTest) {}

    private static final Path NOT_FOUND = Paths.get("");
}
