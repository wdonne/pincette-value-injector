package net.pincette.vi;

import static java.util.Objects.hash;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.crd.generator.annotation.PreserveUnknownFields;
import io.fabric8.generator.annotation.Required;
import java.util.List;

public class ValueInjectorSpec {
  @JsonProperty("from")
  @Required
  public FromReference from;

  @JsonProperty("pipeline")
  @PreserveUnknownFields
  public List<Object> pipeline;

  @JsonProperty("to")
  @Required
  public ToReference to;

  @Override
  public boolean equals(final Object o) {
    return this == o
        || (o instanceof ValueInjectorSpec
            && from.equals(((ValueInjectorSpec) o).from)
            && to.equals(((ValueInjectorSpec) o).to));
  }

  @Override
  public int hashCode() {
    return hash(from, to);
  }

  public static class FromReference {
    @JsonProperty("apiVersion")
    public String apiVersion = "v1";

    @JsonProperty("kind")
    @Required
    public String kind;

    @JsonProperty("name")
    public String name;

    @JsonProperty("namespace")
    public String namespace;

    public String toString() {
      return "(apiVersion: "
          + apiVersion
          + ", kind: "
          + kind
          + ", name: "
          + name
          + ", namespace: "
          + namespace
          + ")";
    }
  }

  public static class ToReference {
    @JsonProperty("apiVersion")
    public String apiVersion = "v1";

    @JsonProperty("kind")
    @Required
    public String kind;

    @JsonProperty("name")
    @Required
    public String name;

    @JsonProperty("namespace")
    public String namespace;

    public String toString() {
      return "(apiVersion: "
          + apiVersion
          + ", kind: "
          + kind
          + ", name: "
          + name
          + ", namespace: "
          + namespace
          + ")";
    }
  }
}
