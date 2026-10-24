// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;

/** Dependency-free synthetic regression suite. Run with javac/java, not Gradle. */
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
            System.out.println("PASS: " + checks + " native provenance checks");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
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
