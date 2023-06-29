package net.pincette.vi;

import static java.util.Objects.hash;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import java.util.Objects;

@Group("pincette.net")
@Version("v1")
public class ValueInjector extends CustomResource<ValueInjectorSpec, ValueInjectorStatus> {
  @Override
  public boolean equals(Object o) {
    return this == o
        || (o instanceof ValueInjector
            && getMetadata().getName().equals(((ValueInjector) o).getMetadata().getName())
            && Objects.equals(
                getMetadata().getNamespace(), ((ValueInjector) o).getMetadata().getNamespace()));
  }

  @Override
  public int hashCode() {
    return hash(getMetadata().getName(), getMetadata().getNamespace());
  }
}
