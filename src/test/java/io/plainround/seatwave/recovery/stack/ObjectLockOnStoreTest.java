package io.plainround.seatwave.recovery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Section 10's rule on the lab's bucket: a backup cannot be deleted before its retention ends, not
 * even with the store's root key. A plain delete only hides the object behind a delete marker.
 */
@Tag("stack")
class ObjectLockOnStoreTest {

  private static final Pattern VERSION =
      Pattern.compile("<Version>.*?<Key>([^<]+)</Key>.*?<VersionId>([^<]+)</VersionId>");

  @Test
  void refusesToDeleteAnArchivedVersionUnderRetention() {
    LabStack.pgbackrest("check");
    Matcher version =
        VERSION.matcher(LabStack.s3("GET", "?versions&prefix=seatwave/archive/seatwave/").output());
    assertThat(version.find()).isTrue();

    LabStack.Ran deleted =
        LabStack.s3("DELETE", "/" + version.group(1) + "?versionId=" + version.group(2));

    assertThat(deleted.status()).isEqualTo(403);
    assertThat(deleted.output()).contains("COMPLIANCE retention");
  }
}
