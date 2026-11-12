package io.plainround.seatwave.recovery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every settings problem is reported together, by key, and no password is ever printed. */
class StackSettingsTest {

  @TempDir Path folder;

  @Test
  void reportsEveryMissingKeyAtOnce() throws Exception {
    Path settings = folder.resolve("empty.properties");
    Files.writeString(settings, "", StandardCharsets.UTF_8);

    assertThat(problems(settings))
        .hasSize(17)
        .contains("db.primaryUrl is missing", "promotion.sequenceMargin is missing");
  }

  @Test
  void refusesAnAddressOtherThanLoopbackAndValuesOutOfRange() throws Exception {
    Path settings =
        settingsWith("db.copyUrl=jdbc:postgresql://10.0.0.5:24012/seatwave\npool.size=0\n");

    assertThat(problems(settings))
        .containsExactlyInAnyOrder(
            "db.copyUrl must name the loopback address 127.0.0.1, found jdbc:postgresql://10.0.0.5:24012/seatwave",
            "pool.size must be 1 to 64, found 0");
  }

  @Test
  void readsPasswordsFromTheirFilesAndNeverPrintsThem() throws Exception {
    StackSettings loaded =
        StackSettings.load(settingsWith(""))
            .orOnErrorThrow(problems -> new IllegalStateException(problems.toString()));

    assertThat(loaded.app().password()).isEqualTo("test-only-password");
    assertThat(loaded.toString()).doesNotContain("test-only-password");
  }

  private Path settingsWith(String overrides) throws Exception {
    Path password = folder.resolve("lab.password");
    Files.writeString(password, "test-only-password\n", StandardCharsets.UTF_8);
    String file = password.toString();
    StringBuilder text = new StringBuilder();
    for (String line :
        Files.readAllLines(Path.of("config/stack.properties"), StandardCharsets.UTF_8)) {
      String key = line.contains("=") ? line.substring(0, line.indexOf('=')) : "";
      if (overrides.contains(key + "=") && !key.isEmpty()) {
        continue;
      }
      text.append(key.endsWith("PasswordFile") ? key + "=" + file : line).append('\n');
    }
    Path settings = folder.resolve("stack.properties");
    Files.writeString(settings, text + overrides, StandardCharsets.UTF_8);
    return settings;
  }

  private static List<String> problems(Path settings) {
    return StackSettings.load(settings)
        .map(loaded -> List.<String>of())
        .recoverError(found -> found);
  }
}
