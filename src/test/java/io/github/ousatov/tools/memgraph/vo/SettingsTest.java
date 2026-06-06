package io.github.ousatov.tools.memgraph.vo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Settings}.
 *
 * @author Oleksii Usatov
 */
class SettingsTest {

  @Test
  void normalRunsAreIncrementalByDefault() {
    assertTrue(Settings.def().incremental());
    assertTrue(Settings.applySchemaOnly().incremental());
  }

  @Test
  void wipeRunsDisableIncrementalSkipping() {
    assertFalse(Settings.wipeAllAndApplySchema().incremental());
    assertFalse(Settings.wipeProjCodeOnly().incremental());
    assertFalse(new Settings(false, false, false, true, false).incremental());
    assertFalse(
        new Settings(
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                EmbeddingSettings.disabled(),
                EmbeddingSettings.disabled())
            .incremental());
    assertFalse(
        new Settings(
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                EmbeddingSettings.disabled(),
                EmbeddingSettings.disabled())
            .incremental());
  }
}
