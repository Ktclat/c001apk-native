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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/** Offline metadata only. Never reads remotes, identities, environment values or local properties. */
public final class NativeBuildProvenance {
    public static final String UNKNOWN = "unavailable";
    public static final String SCOPE = "main-source-whitelist-v1";
    // Deliberately NOT a full APK/reproducible-build hash. Fonts use a manifest; this closed
    // artwork inventory additionally contributes every verified original PNG's actual bytes.
    public static final String EMOTICON_MANIFEST = "app/tools/emoticons.sha256";
    public static final String EMOTICON_DIRECTORY = "app/src/main/res/drawable-nodpi";
    public static final String EMOTICON_MANIFEST_SHA256 = "d866a810fcb82f2325b52c30a57e9abf9c0cd99e2bcea098e751bfefdc2408b9";
    private static final int EMOTICON_COUNT = 183;
    private static final long EMOTICON_BYTES = 1_702_238;
    private static final Pattern EMOTICON_RECORD = Pattern.compile("([0-9a-f]{64})  ((?:coolapk_emotion_|niuniu_emotion_|c_)[a-z0-9_]+\\.png)");
    private static final List<String> FILES = List.of(
        "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts",
        "buildSrc/build.gradle.kts", "app/src/main/AndroidManifest.xml", "app/tools/fonts.sha256", EMOTICON_MANIFEST
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
            boolean namedArtwork = false;
            for (String tree : TREES) {
                Path directory = root.resolve(tree);
                checkAncestors(root, directory);
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return UNKNOWN;
                try (var paths = Files.walk(directory)) {
                    for (Path file : paths.sorted().toList()) {
                        if (Files.isSymbolicLink(file)) throw new IOException("Symlink in source tree");
                        if (file.startsWith(root.resolve("app/src/main/res")) && isEmoticonName(file.getFileName().toString())) namedArtwork = true;
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && allowed(root.relativize(file).toString().replace('\\', '/'))) inputs.add(file);
                    }
                }
            }
            // Tiny legacy/synthetic source fixtures may have no artwork feature at all. Once any
            // catalog/inventory/artwork exists, absence or invalidity is never silently omitted.
            if (namedArtwork || Files.exists(root.resolve(EMOTICON_MANIFEST), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve(EMOTICON_DIRECTORY), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve("app/src/main/java/io/github/ktclat/c001apk/nativeapp/ui/InlineEmoticons.kt"), LinkOption.NOFOLLOW_LINKS)) {
                inputs.addAll(verifiedEmoticons(root));
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

    /** Exact pinned original artwork; no network, arbitrary assets, aliases or symlink following. */
    public static List<Path> verifiedEmoticons(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        for (Path parent = root; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Symlinked artwork root");
        }
        Path manifest = root.resolve(EMOTICON_MANIFEST);
        Path directory = root.resolve(EMOTICON_DIRECTORY);
        checkAncestors(root, manifest); checkAncestors(root, directory);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > 65536
            || !sha256(manifest).equals(EMOTICON_MANIFEST_SHA256)) throw new IOException("Invalid pinned emoticon manifest");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing emoticon directory");
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            var match = EMOTICON_RECORD.matcher(line);
            if (!match.matches() || expected.put(match.group(2), match.group(1)) != null)
                throw new IOException("Invalid or duplicate emoticon inventory entry");
        }
        if (expected.size() != EMOTICON_COUNT) throw new IOException("Incomplete emoticon inventory");
        try (var files = Files.list(directory)) {
            var actual = files.toList();
            if (actual.size() != expected.size()) throw new IOException("Unexpected emoticon directory inventory");
            for (Path file : actual) {
                if (!expected.containsKey(file.getFileName().toString()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected or unsafe emoticon resource");
            }
        }
        // Android qualifier aliases or duplicate XML/PNG names must not shadow the pinned resource.
        try (var files = Files.walk(root.resolve("app/src/main/res"))) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Symlink in resource inventory");
                if (isEmoticonName(file.getFileName().toString()) &&
                    (!file.getParent().equals(directory) || !expected.containsKey(file.getFileName().toString())))
                    throw new IOException("Unlisted or aliased emoticon resource");
            }
        }
        long total = 0;
        List<Path> result = new ArrayList<>();
        for (var record : expected.entrySet()) {
            Path file = directory.resolve(record.getKey());
            checkAncestors(root, file);
            long size = Files.size(file);
            if (size <= 0 || size > EMOTICON_BYTES || total > EMOTICON_BYTES - size)
                throw new IOException("Emoticon byte budget exceeded");
            total += size;
            if (!sha256(file).equals(record.getValue())) throw new IOException("Emoticon original byte mismatch");
            result.add(file);
        }
        if (total != EMOTICON_BYTES) throw new IOException("Incomplete emoticon byte inventory");
        return List.copyOf(result);
    }

    private static boolean isEmoticonName(String name) {
        return name.startsWith("coolapk_emotion_") || name.startsWith("niuniu_emotion_") || name.startsWith("c_");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int length; (length = stream.read(buffer)) >= 0;) digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }

    private static boolean allowed(String path) {
        if (path.startsWith("app/src/main/java/io/github/ktclat/c001apk/nativeapp/") || path.startsWith("buildSrc/src/main/java/io/github/ktclat/build/")) {
            return path.endsWith(".kt") || path.endsWith(".java");
        }
        if (path.startsWith("app/src/main/assets/licenses/")) return path.endsWith(".txt");
        // Generic trees admit XML only. PNG bytes enter solely through verifiedEmoticons above.
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
