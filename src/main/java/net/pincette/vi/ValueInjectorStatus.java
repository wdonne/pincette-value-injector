package net.pincette.vi;

import static net.pincette.vi.Phase.Pending;
import static net.pincette.vi.Phase.Ready;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ValueInjectorStatus {
  @JsonProperty("error")
  public final String error;

  @JsonProperty("phase")
  public final Phase phase;

  @JsonCreator
  public ValueInjectorStatus() {
    this(Ready, null);
  }

  ValueInjectorStatus(final String error) {
    this(Pending, error);
  }

  private ValueInjectorStatus(final Phase phase, final String error) {
    this.phase = phase;
    this.error = error;
  }
}
