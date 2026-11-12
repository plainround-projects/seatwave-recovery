package io.plainround.seatwave.recovery.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfirmManifestTest {

  private static final String ID_1 = "00000000-0000-4000-8000-000000000001";
  private static final String ID_2 = "00000000-0000-4000-8000-000000000002";
  private static final Instant AT = Instant.parse("2026-11-12T19:39:25.008245Z");

  @TempDir Path folder;

  @Test
  void readsBackWhatItWrote() {
    ConfirmManifest written = new ConfirmManifest(7, Map.of(ID_1, AT), Set.of(ID_2));
    Path file = folder.resolve("manifest.txt");
    written.write(file);

    assertThat(ConfirmManifest.read(file).discardError()).hasValue(written);
  }

  @Test
  void refusesAFileOfAnotherKind() throws Exception {
    Path file = folder.resolve("other.txt");
    Files.writeString(file, "row count 3\n", StandardCharsets.UTF_8);

    assertThat(problems(file)).singleElement().asString().contains("does not start with");
  }

  @Test
  void reportsEveryBadLineTogether() throws Exception {
    Path file = folder.resolve("bad.txt");
    Files.write(
        file,
        List.of(
            ConfirmManifest.HEADER,
            "EVENT\t7",
            "ACKNOWLEDGED\t" + ID_1 + "\tyesterday",
            "MAYBE\t" + ID_2 + "\t-",
            "ACKNOWLEDGED\tnot-a-uuid\t-"),
        StandardCharsets.UTF_8);

    assertThat(problems(file))
        .containsExactly(
            "line 3: 'yesterday' is not an ISO-8601 UTC time",
            "line 4: the status 'MAYBE' is not ACKNOWLEDGED or UNCERTAIN",
            "line 5: expected '<ACKNOWLEDGED|UNCERTAIN> TAB <booking uuid> TAB <time>'");
  }

  @Test
  void refusesABookingListedTwice() throws Exception {
    Path file = folder.resolve("twice.txt");
    Files.write(
        file,
        List.of(
            ConfirmManifest.HEADER,
            "EVENT\t7",
            "ACKNOWLEDGED\t" + ID_1 + "\t" + AT,
            "UNCERTAIN\t" + ID_1 + "\t-"),
        StandardCharsets.UTF_8);

    assertThat(problems(file)).containsExactly("line 4: booking " + ID_1 + " appears twice");
  }

  @Test
  void refusesABookingThatIsBothAcknowledgedAndUncertain() {
    assertThatThrownBy(() -> new ConfirmManifest(7, Map.of(ID_1, AT), Set.of(ID_1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<String> problems(Path file) {
    return ConfirmManifest.read(file)
        .map(manifest -> List.<String>of())
        .recoverError(found -> found);
  }
}
