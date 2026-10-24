// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Offline metadata only. Never reads remotes, identities, environment values or local properties. */
public final class NativeBuildProvenance {
    public static final String UNKNOWN = "unavailable";
    public static final String SCOPE = "main-source-whitelist-v1";
    // Deliberately NOT a full APK/reproducible-build hash. Pinned fonts are represented by their manifest.
    private static final List<String> FILES = List.of(
        "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts",
        "buildSrc/build.gradle.kts", "app/src/main/AndroidManifest.xml", "app/tools/fonts.sha256"
    );
    private static final List<String> TREES = List.of(
        "app/src/main/java/io/github/ktclat/c001apk/nativeapp",
        "app/src/main/res", "app/src/main/assets/licenses",
        "buildSrc/src/main/java/io/github/ktclat/build"
    );

    private NativeBuildProvenance() {}

    public record Snapshot(String commit, String branch, String worktree, String fingerprint) {
        public String kotlinSource() {
            return "// Generated locally. No timestamp, paths or device identifiers.\n"
                + "package io.github.ktclat.c001apk.nativeapp.diagnostics\n\n"
                + "internal object NativeBuildInfo {\n"
                + constant("COMMIT", commit) + constant("BRANCH", branch)
                + constant("WORKTREE", worktree) + constant("SOURCE_FINGERPRINT", fingerprint)
                + constant("FINGERPRINT_SCOPE", SCOPE) + "}\n";
        }
    }

    public static Snapshot capture(Path root) {
        String repositoryRoot = git(root, "rev-parse", "--show-toplevel");
        try {
            if (repositoryRoot == null || !Path.of(repositoryRoot).toRealPath().equals(root.toRealPath())) {
                return new Snapshot(UNKNOWN, UNKNOWN, UNKNOWN, fingerprint(root));
            }
        } catch (IOException unavailable) {
            return new Snapshot(UNKNOWN, UNKNOWN, UNKNOWN, fingerprint(root));
        }
        String commit = git(root, "rev-parse", "--verify", "HEAD");
        if (commit == null || !commit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) commit = UNKNOWN;
        String branch = git(root, "rev-parse", "--abbrev-ref", "HEAD");
        if (branch == null) branch = UNKNOWN;
        else if (branch.equals("HEAD")) branch = "detached HEAD";
        else if (!branch.matches("[A-Za-z0-9._/-]{1,128}")) branch = UNKNOWN;
        String status = git(root, "status", "--porcelain=v1", "--untracked-files=all", "--ignore-submodules=none");
        String worktree = status == null ? UNKNOWN : status.isEmpty() ? "clean" : "dirty";
        return new Snapshot(commit, branch, worktree, fingerprint(root));
    }

    public static String fingerprint(Path root) {
        try {
            root = root.toAbsolutePath().normalize();
            // Reject symlinks instead of following a whitelisted name into secrets or a different tree.
            for (Path parent = root; parent != null; parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) return UNKNOWN;
            }
            if (!Files.isRegularFile(root.resolve("app/src/main/AndroidManifest.xml"), LinkOption.NOFOLLOW_LINKS)) return UNKNOWN;
            List<Path> inputs = new ArrayList<>();
            for (String name : FILES) {
                Path file = root.resolve(name);
                checkAncestors(root, file);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) inputs.add(file);
                else if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return UNKNOWN;
            }
            for (String tree : TREES) {
                Path directory = root.resolve(tree);
                checkAncestors(root, directory);
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return UNKNOWN;
                try (var paths = Files.walk(directory)) {
                    for (Path file : paths.sorted().toList()) {
                        if (Files.isSymbolicLink(file)) throw new IOException("Symlink in source tree");
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && allowed(root.relativize(file).toString().replace('\\', '/'))) inputs.add(file);
                    }
                }
            }
            Path sourceRoot = root;
            inputs.sort(Comparator.comparing(p -> sourceRoot.relativize(p).toString().replace('\\', '/')));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((SCOPE + "\0").getBytes(StandardCharsets.UTF_8));
            for (Path file : inputs) {
                String name = root.relativize(file).toString().replace('\\', '/');
                // Frame relative names and content hashes, never absolute paths or filesystem timestamps.
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                MessageDigest content = MessageDigest.getInstance("SHA-256");
                try (var stream = Files.newInputStream(file)) {
                    byte[] buffer = new byte[65536];
                    for (int length; (length = stream.read(buffer)) >= 0;) content.update(buffer, 0, length);
                }
                digest.update(content.digest());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception unavailable) {
            // Do not expose local paths, errors or fallback to misleading commit-only provenance.
            return UNKNOWN;
        }
    }

    private static boolean allowed(String path) {
        if (path.startsWith("app/src/main/java/io/github/ktclat/c001apk/nativeapp/") || path.startsWith("buildSrc/src/main/java/io/github/ktclat/build/")) {
            return path.endsWith(".kt") || path.endsWith(".java");
        }
        if (path.startsWith("app/src/main/assets/licenses/")) return path.endsWith(".txt");
        // Only source-controlled XML UI/config resources. No raw assets, key stores, binaries or fonts.
        return path.startsWith("app/src/main/res/") && path.endsWith(".xml")
            && !path.matches("app/src/main/res/raw(?:-[^/]+)?/.*");
    }

    private static void checkAncestors(Path root, Path path) throws IOException {
        for (Path current = path; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink in whitelist path");
        }
    }

    private static String git(Path root, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Do not let an inherited override silently attribute a different repository to this tree.
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            // Drain concurrently: large dirty trees must not deadlock or be mislabeled clean.
            var output = new java.io.ByteArrayOutputStream();
            var readFailed = new java.util.concurrent.atomic.AtomicBoolean(false);
            Process running = process;
            Thread reader = new Thread(() -> {
                try (var stream = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    for (int size; (size = stream.read(buffer)) >= 0;) {
                        if (output.size() < 65536) output.write(buffer, 0, Math.min(size, 65536 - output.size()));
                    }
                } catch (IOException ignored) { readFailed.set(true); }
            }, "native-provenance-git");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) return null;
            reader.join(1000);
            if (reader.isAlive() || readFailed.get() || process.exitValue() != 0) return null;
            return output.toString(StandardCharsets.UTF_8).strip();
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static String constant(String key, String value) {
        return "    const val " + key + " = \"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r") + "\"\n";
    }
}
