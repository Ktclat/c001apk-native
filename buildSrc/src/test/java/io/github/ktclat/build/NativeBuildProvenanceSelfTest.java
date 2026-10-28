// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;

/** Dependency-free provenance checks: synthetic source fixtures plus real pinned artwork. */
public final class NativeBuildProvenanceSelfTest {
    private static int checks;
    public static void main(String[] arguments) throws Exception {
        Path directory = Files.createTempDirectory("native-provenance-test-");
        try {
            Path one = directory.resolve("one"), two = directory.resolve("different-absolute-path");
            fixture(one); fixture(two);
            String original = NativeBuildProvenance.fingerprint(one);
            check(original.matches("sha256:[a-f0-9]{64}"), "has SHA-256");
            check(original.equals(NativeBuildProvenance.fingerprint(two)), "absolute paths do not enter fingerprint");
            Files.setLastModifiedTime(one.resolve("app/src/main/AndroidManifest.xml"), FileTime.fromMillis(1234));
            check(original.equals(NativeBuildProvenance.fingerprint(one)), "timestamps do not enter fingerprint");
            for (String name : List.of("local.properties", "gradle.properties", ".env", "signing/release.keystore", "app/build/generated/BuildInfo.kt", "app/src/main/assets/private.json", "app/src/main/res/raw/credentials.xml", "app/src/main/res/raw-v26/credentials.xml", "app/src/main/res/font/prepared.otf")) write(one, name, "SECRET-MARKER-DO-NOT-EXPORT");
            check(original.equals(NativeBuildProvenance.fingerprint(one)), "local config, credentials, generated files and binary fonts excluded");
            write(one, "app/src/main/java/io/github/ktclat/c001apk/nativeapp/Example.kt", "val value = 2\n");
            check(!original.equals(NativeBuildProvenance.fingerprint(one)), "source content changes fingerprint");
            fixture(one);
            write(one, "app/src/main/java/io/github/ktclat/c001apk/nativeapp/New.kt", "val extra = true\n");
            check(!original.equals(NativeBuildProvenance.fingerprint(one)), "untracked source additions included");
            Files.delete(one.resolve("app/src/main/java/io/github/ktclat/c001apk/nativeapp/New.kt"));
            Path code = one.resolve("app/src/main/java/io/github/ktclat/c001apk/nativeapp/Example.kt");
            Files.move(code, code.resolveSibling("Renamed.kt"));
            check(!original.equals(NativeBuildProvenance.fingerprint(one)), "relative source names included");
            Files.move(code.resolveSibling("Renamed.kt"), code);
            check(original.equals(NativeBuildProvenance.fingerprint(one)), "same source restores exact fingerprint");
            check(NativeBuildProvenance.capture(one).commit().equals("unavailable"), "archive has unavailable commit");
            check(NativeBuildProvenance.capture(one).worktree().equals("unavailable"), "archive is not mislabeled clean");
            write(one, ".gitignore", "local.properties\ngradle.properties\n.env\nsigning/\napp/build/\napp/src/main/assets/private.json\napp/src/main/res/raw/\napp/src/main/res/font/\n");
            git(one, "init", "-b", "main"); git(one, "add", ".");
            git(one, "-c", "user.name=Synthetic", "-c", "user.email=synthetic@example.invalid", "commit", "-qm", "fixture");
            var clean = NativeBuildProvenance.capture(one);
            check(clean.worktree().equals("clean") && clean.branch().equals("main"), "clean git tree");
            check(clean.commit().matches("[a-f0-9]{40,64}"), "full commit recorded");
            write(one, "notes.txt", "Untracked outside fingerprint\n");
            check(NativeBuildProvenance.capture(one).worktree().equals("dirty"), "untracked unrelated file cannot be mislabeled clean");
            check(NativeBuildProvenance.capture(one).fingerprint().equals(original), "status and scoped fingerprint are independent");
            Files.delete(one.resolve("notes.txt"));
            write(one, "app/src/main/java/io/github/ktclat/c001apk/nativeapp/Example.kt", "val changed = true\n");
            check(NativeBuildProvenance.capture(one).worktree().equals("dirty"), "unstaged source is dirty");
            git(one, "add", ".");
            check(NativeBuildProvenance.capture(one).worktree().equals("dirty"), "staged source is dirty");
            git(one, "-c", "user.name=Synthetic", "-c", "user.email=synthetic@example.invalid", "commit", "-qm", "change");
            git(one, "checkout", "--detach", "--quiet");
            check(NativeBuildProvenance.capture(one).branch().equals("detached HEAD"), "detached HEAD labeled honestly");
            Path nested = one.resolve("exported-project"); fixture(nested);
            check(NativeBuildProvenance.capture(nested).commit().equals("unavailable"), "parent repository cannot supply false provenance");
            Path link = two.resolve("app/src/main/java/io/github/ktclat/c001apk/nativeapp/Linked.kt");
            Files.createSymbolicLink(link, one.resolve("local.properties"));
            check(NativeBuildProvenance.fingerprint(two).equals("unavailable"), "symlink into secrets rejected");
            Files.delete(link);
            String generated = NativeBuildProvenance.capture(one).kotlinSource();
            check(!generated.contains(directory.toString()) && !generated.contains("SECRET-MARKER") && !generated.contains("synthetic@example"), "generated constants have no paths, secrets or identity");
            emoticonChecks(directory.resolve("artwork"), findArtworkSource(arguments));
            System.out.println("PASS: " + checks + " native provenance checks");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    /** These checks use the real pinned original files, not a manifest of invented test artwork. */
    private static void emoticonChecks(Path root, Path source) throws Exception {
        fixture(root);
        Path manifest = root.resolve(NativeBuildProvenance.EMOTICON_MANIFEST);
        Path images = root.resolve(NativeBuildProvenance.EMOTICON_DIRECTORY);
        Files.createDirectories(manifest.getParent()); Files.createDirectories(images);
        Files.copy(source.resolve(NativeBuildProvenance.EMOTICON_MANIFEST), manifest);
        for (Path original : NativeBuildProvenance.verifiedEmoticons(source)) Files.copy(original, images.resolve(original.getFileName()));
        List<Path> originals = NativeBuildProvenance.verifiedEmoticons(root);
        check(originals.size() == 183, "exact 183 original artwork files verified");
        String fingerprint = NativeBuildProvenance.fingerprint(root);
        check(fingerprint.matches("sha256:[a-f0-9]{64}"), "pinned artwork source has a fingerprint");
        List<Path> inputs = new ArrayList<>(List.of(root.resolve("app/src/main/AndroidManifest.xml"),
            root.resolve("app/src/main/java/io/github/ktclat/c001apk/nativeapp/Example.kt"),
            root.resolve("app/tools/fonts.sha256"), manifest));
        String manifestOnly = framedFingerprint(root, inputs);
        inputs.addAll(originals);
        check(fingerprint.equals(framedFingerprint(root, inputs)) && !fingerprint.equals(manifestOnly),
            "all 183 actual PNG byte hashes contribute in addition to their manifest");
        Path first = originals.get(0);
        byte[] originalBytes = Files.readAllBytes(first), originalManifest = Files.readAllBytes(manifest);
        byte[] changed = originalBytes.clone(); changed[changed.length - 1] ^= 1;
        Files.write(first, changed);
        check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "modified artwork bytes fail closed");
        Files.write(first, originalBytes);
        check(NativeBuildProvenance.fingerprint(root).equals(fingerprint), "restored original bytes restore fingerprint");
        Path saved = root.resolve("saved-original.png");
        Files.move(first, saved);
        check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "missing original PNG rejected");
        Files.move(saved, first);
        Path extra = images.resolve("c_unlisted.png"); Files.write(extra, originalBytes);
        check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "extra artwork PNG rejected");
        Files.delete(extra);
        Path alias = root.resolve("app/src/main/res/drawable-hdpi").resolve(first.getFileName());
        Files.createDirectories(alias.getParent()); Files.write(alias, originalBytes);
        check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "qualified alias cannot shadow original resource");
        Files.delete(alias); Files.delete(alias.getParent());
        Files.delete(first); Files.createSymbolicLink(first, source.resolve(NativeBuildProvenance.EMOTICON_DIRECTORY).resolve(first.getFileName()));
        check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "symlink artwork file rejected");
        Files.delete(first); Files.write(first, originalBytes);
        Files.delete(manifest);
        check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "missing manifest cannot silently omit existing artwork");
        Files.write(manifest, originalManifest);
        String manifestText = new String(originalManifest, StandardCharsets.UTF_8);
        for (String invalid : List.of(manifestText + manifestText.lines().findFirst().orElseThrow() + "\n",
            manifestText.substring(manifestText.indexOf('\n') + 1), manifestText.replaceFirst("  c_", "  ../c_"),
            "0" + manifestText.substring(1))) {
            Files.writeString(manifest, invalid);
            check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "duplicate/incomplete/path/changed inventory fails closed");
        }
        Files.delete(manifest); Files.createSymbolicLink(manifest, source.resolve(NativeBuildProvenance.EMOTICON_MANIFEST));
        check(NativeBuildProvenance.fingerprint(root).equals("unavailable"), "symlink inventory rejected");
        Files.delete(manifest); Files.write(manifest, originalManifest);
        Path rootAlias = root.resolveSibling("artwork-linked-root"); Files.createSymbolicLink(rootAlias, root);
        check(NativeBuildProvenance.fingerprint(rootAlias).equals("unavailable"), "symlink project parent rejected");
        Files.delete(rootAlias);
        Path missing = root.resolveSibling("catalog-without-artwork"); fixture(missing);
        write(missing, "app/src/main/java/io/github/ktclat/c001apk/nativeapp/ui/InlineEmoticons.kt", "object InlineEmoticons\n");
        check(NativeBuildProvenance.fingerprint(missing).equals("unavailable"), "catalog without inventory cannot get normal fingerprint");
        check(NativeBuildProvenance.fingerprint(root).equals(fingerprint), "negative inventory tests leave original source fingerprint restored");
    }

    private static Path findArtworkSource(String[] arguments) {
        if (arguments.length > 1) throw new IllegalArgumentException("At most one source-root argument");
        Path root = Path.of(arguments.length == 1 ? arguments[0] : ".").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve(NativeBuildProvenance.EMOTICON_MANIFEST))) root = root.getParent();
        if (root == null) throw new IllegalArgumentException("Run from source root or pass its path; original artwork is required");
        return root;
    }

    private static String framedFingerprint(Path root, List<Path> inputs) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((NativeBuildProvenance.SCOPE + "\0").getBytes(StandardCharsets.UTF_8));
        for (Path input : inputs.stream().sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/'))).toList()) {
            digest.update(root.relativize(input).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void fixture(Path root) throws Exception {
        write(root, "app/src/main/AndroidManifest.xml", "<manifest/>\n");
        write(root, "app/src/main/java/io/github/ktclat/c001apk/nativeapp/Example.kt", "val value = 1\n");
        write(root, "app/tools/fonts.sha256", "pinned-font-manifest\n");
    }
    private static void write(Path root, String name, String value) throws Exception {
        Path file = root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, value);
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message); checks++;
    }
    private static void git(Path root, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
    }
}
