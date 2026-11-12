package io.plainround.seatwave.recovery.backup;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A base backup of the drill store: a copy of every data file plus a manifest that names each file
 * with its size and SHA-256, the moment the copy started and the first log sequence number a
 * restore must replay from. It is the small version of {@code pg_basebackup}'s {@code
 * backup_manifest}. The point of the class is {@link #restoreFiles}: it copies a file back only
 * while checking it against the manifest, so an unreadable backup is found at restore time, loudly,
 * and never handed on as if it were good. Stateless and thread-safe.
 */
public final class BaseBackup {

  /** When the backup started and the first change a restore must replay on top of it. */
  public record Label(Instant takenAt, long startLsn) {
    /** Checks the label at construction. */
    public Label {
      Objects.requireNonNull(takenAt, "takenAt");
      if (startLsn < 1) {
        throw new IllegalArgumentException("startLsn must be 1 or more, was " + startLsn);
      }
    }
  }

  /** Every file matched the manifest; the copy in the target folder may be used. */
  public record Verified(Label label, int files, long bytes) {}

  /** The backup cannot be trusted; the target folder was emptied again. */
  public record Rejected(String reason) {}

  /** The manifest's file name, as in PostgreSQL. */
  public static final String MANIFEST_NAME = "backup_manifest";

  private static final String MANIFEST_HEADER = "seatwave-backup-manifest 1";
  private static final String TAKEN_AT = "taken-at ";
  private static final String START_LSN = "start-lsn ";
  private static final String FILE_ENTRY = "file ";
  private static final String MANIFEST_CHECKSUM = "manifest-sha256 ";
  private static final String DIGEST_ALGORITHM = "SHA-256";
  private static final int COPY_BUFFER_BYTES = 1 << 16;
  private static final int FILE_ENTRY_FIELDS = 4;

  private BaseBackup() {}

  /**
   * Copies every file of {@code dataDirectory} into {@code backupDirectory} and writes the
   * manifest.
   */
  public static void take(Path dataDirectory, Path backupDirectory, Label label) {
    List<String> lines = new ArrayList<>();
    lines.add(MANIFEST_HEADER);
    lines.add(TAKEN_AT + label.takenAt());
    lines.add(START_LSN + label.startLsn());
    for (Path dataFile : filesIn(dataDirectory)) {
      Path copy = backupDirectory.resolve(dataFile.getFileName());
      String digest = copyWithDigest(dataFile, copy);
      lines.add(FILE_ENTRY + dataFile.getFileName() + " " + size(copy) + " " + digest);
    }
    // The manifest checks itself too: a damaged manifest must not vouch for damaged files.
    lines.add(MANIFEST_CHECKSUM + digestOf(String.join("\n", lines)));
    write(backupDirectory.resolve(MANIFEST_NAME), String.join("\n", lines) + "\n");
  }

  /**
   * Copies the backup into the empty folder {@code targetDirectory}, checking every file's size and
   * SHA-256 against the manifest while it copies. On any mismatch, a missing file or an extra file,
   * it deletes what it copied and returns the reason.
   *
   * @return what was restored and verified, or why the backup was rejected
   */
  public static Result<Verified, Rejected> restoreFiles(
      Path backupDirectory, Path targetDirectory) {
    Path manifestFile = backupDirectory.resolve(MANIFEST_NAME);
    if (!Files.isRegularFile(manifestFile)) {
      return Result.error(new Rejected("no " + MANIFEST_NAME + " in the backup"));
    }
    List<String> lines = readLines(manifestFile);
    String checksumLine = lines.getLast();
    String body = String.join("\n", lines.subList(0, lines.size() - 1));
    if (!checksumLine.equals(MANIFEST_CHECKSUM + digestOf(body))) {
      return Result.error(new Rejected("the manifest fails its own checksum"));
    }
    Label label =
        new Label(
            Instant.parse(valueAfter(lines, TAKEN_AT)),
            Long.parseLong(valueAfter(lines, START_LSN)));
    List<String[]> entries =
        lines.stream()
            .filter(line -> line.startsWith(FILE_ENTRY))
            .map(line -> line.split(" "))
            .toList();
    long bytesRestored = 0;
    for (String[] entry : entries) {
      if (entry.length != FILE_ENTRY_FIELDS) {
        return rejectAndClean(targetDirectory, "a malformed file line in the manifest");
      }
      // A file name from the manifest must name a file in the backup folder, never a path out of
      // it.
      if (entry[1].contains("/") || entry[1].contains("\\") || entry[1].startsWith(".")) {
        return rejectAndClean(
            targetDirectory, "the manifest names a path, not a file: " + entry[1]);
      }
      Path source = backupDirectory.resolve(entry[1]);
      if (!Files.isRegularFile(source)) {
        return rejectAndClean(
            targetDirectory, entry[1] + " is in the manifest but not in the backup");
      }
      String digest = copyWithDigest(source, targetDirectory.resolve(entry[1]));
      long bytes = size(source);
      if (bytes != Long.parseLong(entry[2]) || !digest.equals(entry[3])) {
        return rejectAndClean(
            targetDirectory, entry[1] + " does not match the manifest (size or SHA-256)");
      }
      bytesRestored += bytes;
    }
    long filesInBackup = filesIn(backupDirectory).size() - 1;
    if (filesInBackup != entries.size()) {
      return rejectAndClean(targetDirectory, "the backup holds files the manifest does not name");
    }
    return Result.success(new Verified(label, entries.size(), bytesRestored));
  }

  private static Result<Verified, Rejected> rejectAndClean(Path targetDirectory, String reason) {
    for (Path copied : filesIn(targetDirectory)) {
      try {
        Files.delete(copied);
      } catch (IOException deleteFailure) {
        throw new UncheckedIOException(
            "cannot clean " + copied + " after: " + reason, deleteFailure);
      }
    }
    return Result.error(new Rejected(reason));
  }

  private static String valueAfter(List<String> lines, String prefix) {
    return lines.stream()
        .filter(line -> line.startsWith(prefix))
        .findFirst()
        .map(line -> line.substring(prefix.length()))
        .orElseThrow(() -> new IllegalStateException("manifest has no line " + prefix.trim()));
  }

  private static String copyWithDigest(Path source, Path target) {
    MessageDigest digest = newDigest();
    try (InputStream in = new DigestInputStream(Files.newInputStream(source), digest);
        OutputStream out = Files.newOutputStream(target)) {
      byte[] buffer = new byte[COPY_BUFFER_BYTES];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    } catch (IOException copyFailure) {
      throw new UncheckedIOException("cannot copy " + source + " to " + target, copyFailure);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String digestOf(String text) {
    return HexFormat.of().formatHex(newDigest().digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("every JDK must provide " + DIGEST_ALGORITHM, missing);
    }
  }

  private static List<Path> filesIn(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException listFailure) {
      throw new UncheckedIOException("cannot list " + directory, listFailure);
    }
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException sizeFailure) {
      throw new UncheckedIOException("cannot read the size of " + file, sizeFailure);
    }
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read " + file, readFailure);
    }
  }

  private static void write(Path file, String text) {
    try {
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException writeFailure) {
      throw new UncheckedIOException("cannot write " + file, writeFailure);
    }
  }
}
