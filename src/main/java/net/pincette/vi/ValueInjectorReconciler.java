package net.pincette.vi;

import static io.fabric8.kubernetes.client.Watcher.Action.ADDED;
import static io.fabric8.kubernetes.client.Watcher.Action.MODIFIED;
import static io.fabric8.kubernetes.client.dsl.base.PatchType.SERVER_SIDE_APPLY;
import static io.javaoperatorsdk.operator.api.reconciler.DeleteControl.defaultDelete;
import static io.javaoperatorsdk.operator.api.reconciler.UpdateControl.noUpdate;
import static java.lang.Integer.MAX_VALUE;
import static java.time.Duration.ofSeconds;
import static java.util.Optional.ofNullable;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Logger.getLogger;
import static net.pincette.json.JsonUtil.createObjectBuilder;
import static net.pincette.json.JsonUtil.from;
import static net.pincette.json.JsonUtil.string;
import static net.pincette.mongo.Util.fromMongoDB;
import static net.pincette.mongo.Util.toMongoDB;
import static net.pincette.mongo.streams.Pipeline.create;
import static net.pincette.rs.Box.box;
import static net.pincette.rs.Chain.with;
import static net.pincette.rs.DequePublisher.dequePublisher;
import static net.pincette.rs.LambdaSubscriber.lambdaSubscriber;
import static net.pincette.rs.Mapper.map;
import static net.pincette.rs.streams.Message.message;
import static net.pincette.util.Collections.list;
import static net.pincette.util.Collections.set;
import static net.pincette.util.Or.tryWith;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.StreamUtil.concat;
import static net.pincette.util.StreamUtil.last;
import static net.pincette.util.Util.splitPair;
import static net.pincette.util.Util.tryToDo;
import static net.pincette.util.Util.tryToDoForever;
import static net.pincette.util.Util.tryToGet;
import static net.pincette.util.Util.tryToGetRethrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.Watchable;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.retry.GradualRetry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow.Processor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.json.JsonObject;
import javax.json.JsonValue;
import net.pincette.json.JsonUtil;
import net.pincette.rs.DequePublisher;
import net.pincette.rs.PassThrough;
import net.pincette.rs.streams.Message;
import net.pincette.util.Collections;
import net.pincette.util.ImmutableBuilder;
import net.pincette.util.Pair;
import net.pincette.util.Util;
import net.pincette.vi.ValueInjectorSpec.FromReference;
import net.pincette.vi.ValueInjectorSpec.ToReference;

@io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
@GradualRetry(maxAttempts = MAX_VALUE)
public class ValueInjectorReconciler implements Reconciler<ValueInjector>, Cleaner<ValueInjector> {
  private static final Duration BACKOFF = ofSeconds(5);
  private static final String FIELD_MANAGER = "pincette.net/value-injector";
  private static final String FROM = "from";
  private static final Logger LOGGER = getLogger("net.pincette.vi");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final PatchContext PATCH_CONTEXT =
      new PatchContext.Builder()
          .withPatchType(SERVER_SIDE_APPLY)
          .withFieldManager(FIELD_MANAGER)
          .withForce(true)
          .build();
  private static final String TO = "to";

  private final KubernetesClient client;
  private final Map<ValueInjector, Watch> watchersFrom = new HashMap<>();
  private final Map<ValueInjector, Watch> watchersTo = new HashMap<>();

  ValueInjectorReconciler(final KubernetesClient client) {
    this.client = client;
  }

  private static void apply(
      final JsonObject json, final ValueInjector resource, final KubernetesClient client) {
    final String asText = string(json);
    final ToReference reference = resource.getSpec().to;

    LOGGER.info(() -> "Applying " + asText + " to " + reference);

    tryToDo(
        () ->
            genericClient(reference.kind, reference.namespace, reference.apiVersion, client)
                .inNamespace(reference.namespace)
                .withName(reference.name)
                .patch(PATCH_CONTEXT, asText),
        ValueInjectorReconciler::logException);
  }

  private static BiConsumer<GenericKubernetesResource, GenericKubernetesResource> applyChange(
      final ValueInjector resource, final KubernetesClient client) {
    final DequePublisher<JsonObject> publisher = dequePublisher();

    with(publisher)
        .map(pipeline(resource))
        .map(json -> json.getJsonObject(TO))
        .get()
        .subscribe(lambdaSubscriber(json -> apply(fromMongoDB(json), resource, client)));

    return (from, to) -> {
      LOGGER.info(() -> "Received resource " + from);

      tryToGet(
              () -> string(toJson(from)),
              e -> {
                logException(e);
                return null;
              })
          .flatMap(
              json ->
                  createMessage(
                          from, () -> to != null ? to : getResource(resource.getSpec().to, client))
                      .map(m -> pair(json, m)))
          .ifPresent(
              pair -> {
                LOGGER.info(() -> "Received JSON " + pair.first);
                LOGGER.info(() -> "Pipeline message " + string(pair.second));
                publisher.getDeque().addFirst(pair.second);
              });
    };
  }

  private static int compareManagedFieldsEntry(
      final ManagedFieldsEntry e1, final ManagedFieldsEntry e2) {
    return tryWith(
            () ->
                Optional.of(
                        ofNullable(e1.getTime())
                            .orElse("")
                            .compareTo(ofNullable(e2.getTime()).orElse("")))
                    .filter(r -> r != 0))
        .or(() -> Optional.of(e1.getManager()).filter(FIELD_MANAGER::equals).map(m -> 1))
        .or(() -> Optional.of(e2.getManager()).filter(FIELD_MANAGER::equals).map(m -> -1))
        .get()
        .orElse(0);
  }

  private static Optional<JsonObject> createMessage(
      final GenericKubernetesResource from, final Supplier<GenericKubernetesResource> resource) {
    return ofNullable(resource.get())
        .map(
            r ->
                toMongoDB(
                    createObjectBuilder().add(FROM, toJson(from)).add(TO, toJson(r)).build()));
  }

  private static MixedOperation<
          GenericKubernetesResource,
          GenericKubernetesResourceList,
          Resource<GenericKubernetesResource>>
      genericClient(
          final String kind,
          final String namespace,
          final String apiVersion,
          final KubernetesClient client) {
    return client.genericKubernetesResources(
        resourceDefinitionContext(kind, namespace, apiVersion));
  }

  private static GenericKubernetesResource getResource(
      final ToReference reference, final KubernetesClient client) {
    return getResource(
        reference.kind, reference.name, reference.namespace, reference.apiVersion, client);
  }

  private static GenericKubernetesResource getResource(
      final String kind,
      final String name,
      final String namespace,
      final String apiVersion,
      final KubernetesClient client) {
    final GenericKubernetesResource result =
        Util.with(
                () -> genericClient(kind, namespace, apiVersion, client),
                c -> namespace != null ? c.inNamespace(namespace).withName(name) : c.withName(name))
            .get();

    if (result == null) {
      LOGGER.info(
          () ->
              "Resource (apiVersion: "
                  + apiVersion
                  + ", kind: "
                  + kind
                  + ", name: "
                  + name
                  + ", namespace: "
                  + namespace
                  + ") does not exist.");
    }

    return result;
  }

  private static <T> BiConsumer<Action, T> handleEvent(
      final Set<Action> actions, final Consumer<T> applyChange) {
    return (action, resource) -> {
      if (actions.contains(action)) {
        applyChange.accept(resource);
      }
    };
  }

  private static boolean isInjected(final GenericKubernetesResource resource) {
    return last(resource.getMetadata().getManagedFields().stream()
            .sorted(ValueInjectorReconciler::compareManagedFieldsEntry))
        .filter(ValueInjectorReconciler::isInjected)
        .isPresent();
  }

  private static boolean isInjected(final ManagedFieldsEntry entry) {
    return ofNullable(entry.getManager()).filter(m -> m.equals(FIELD_MANAGER)).isPresent();
  }

  private static void logException(final Throwable t) {
    LOGGER.log(SEVERE, t, t::getMessage);
  }

  private static List<String> managedFields(final String prefix) {
    return list(
        prefix + ".metadata.uid",
        prefix + ".metadata.resourceVersion",
        prefix + ".metadata.creationTimestamp",
        prefix + ".metadata.managedFields",
        prefix + ".metadata.selfLink");
  }

  private static Processor<JsonObject, Message<String, JsonObject>> messages() {
    return map(json -> message("", json));
  }

  private static Processor<JsonObject, JsonObject> pipeline(final ValueInjector resource) {
    return ofNullable(resource.getSpec().pipeline)
        .map(
            pipeline ->
                create(
                    from(concat(removeManagedFields(), from(pipeline).stream())),
                    new net.pincette.mongo.streams.Context()))
        .map(processor -> box(messages(), processor))
        .map(processor -> box(processor, values()))
        .orElseGet(PassThrough::new);
  }

  private static Stream<JsonValue> removeManagedFields() {
    return Stream.of(
        from(
            Collections.map(
                pair("$unset", Collections.concat(managedFields("from"), managedFields("to"))))));
  }

  private static ResourceDefinitionContext resourceDefinitionContext(
      final String kind, final String namespace, final String apiVersion) {
    final Optional<Pair<String, String>> versionAndGroup =
        ofNullable(apiVersion).map(a -> splitPair(a, "/"));

    return ImmutableBuilder.create(Builder::new)
        .update(b -> b.withKind(kind))
        .updateIf(() -> ofNullable(namespace), (b, v) -> b.withNamespaced(true))
        .updateIf(
            () -> versionAndGroup.map(pair -> pair.second != null ? pair.second : pair.first),
            Builder::withVersion)
        .updateIf(
            () -> versionAndGroup.map(pair -> pair.second != null ? pair.first : null),
            Builder::withGroup)
        .build()
        .build();
  }

  private static JsonObject toJson(final GenericKubernetesResource resource) {
    return tryToGetRethrow(() -> MAPPER.writeValueAsString(resource))
        .flatMap(JsonUtil::from)
        .filter(JsonUtil::isObject)
        .map(JsonValue::asJsonObject)
        .orElseThrow();
  }

  private static Processor<Message<String, JsonObject>, JsonObject> values() {
    return map(message -> message.value);
  }

  private static Watchable<GenericKubernetesResource> watchable(
      final FromReference reference, final KubernetesClient client) {
    return Util.with(
        () -> genericClient(reference.kind, reference.namespace, reference.apiVersion, client),
        c ->
            tryWith(
                    () ->
                        reference.namespace != null && reference.name != null
                            ? (Watchable<GenericKubernetesResource>)
                                c.inNamespace(reference.namespace).withName(reference.name)
                            : null)
                .or(
                    () ->
                        reference.namespace == null && reference.name != null
                            ? (Watchable<GenericKubernetesResource>) c.withName(reference.name)
                            : null)
                .or(
                    () ->
                        reference.namespace != null && reference.name == null
                            ? (Watchable<GenericKubernetesResource>)
                                c.inNamespace(reference.namespace)
                            : null)
                .get()
                .orElse(c));
  }

  private static Watchable<GenericKubernetesResource> watchable(
      final ToReference reference, final KubernetesClient client) {
    return Util.with(
        () -> genericClient(reference.kind, reference.namespace, reference.apiVersion, client),
        c ->
            reference.namespace != null
                ? c.inNamespace(reference.namespace).withName(reference.name)
                : c.withName(reference.name));
  }

  private static <T> Watcher<T> watcher(final BiConsumer<Action, T> fn) {
    return new Watcher<>() {
      private boolean closed;

      public void eventReceived(final Action action, final T resource) {
        tryToDoForever(
            () -> {
              if (!closed) {
                fn.accept(action, resource);
              }
            },
            BACKOFF,
            ValueInjectorReconciler::logException);
      }

      public void onClose(WatcherException cause) {
        closed = true;
      }
    };
  }

  public DeleteControl cleanup(final ValueInjector resource, final Context<ValueInjector> context) {
    removeWatch(resource);

    return defaultDelete();
  }

  public UpdateControl<ValueInjector> reconcile(
      final ValueInjector resource, final Context<ValueInjector> context) {
    final BiConsumer<GenericKubernetesResource, GenericKubernetesResource> applyChange =
        applyChange(resource, client);
    final FromReference from = resource.getSpec().from;

    removeWatch(resource);
    watchersFrom.put(
        resource,
        watchable(resource.getSpec().from, client)
            .watch(
                watcher(
                    handleEvent(
                        set(ADDED, MODIFIED), fromRes -> applyChange.accept(fromRes, null)))));

    if (from.name != null) {
      final Supplier<GenericKubernetesResource> getFrom =
          () -> getResource(from.kind, from.name, from.namespace, from.apiVersion, client);

      watchersTo.put(
          resource,
          watchable(resource.getSpec().to, client)
              .watch(
                  watcher(
                      handleEvent(
                          set(ADDED, MODIFIED),
                          toRes -> {
                            if (!isInjected(toRes)) {
                              applyChange.accept(getFrom.get(), toRes);
                            }
                          }))));

      applyChange.accept(getFrom.get(), null);
    }

    return noUpdate();
  }

  private void removeWatch(final ValueInjector resource) {
    LOGGER.info(() -> "Stopping watch for " + resource.getSpec().from);
    removeWatch(resource, watchersFrom);
    LOGGER.info(() -> "Stopping watch for " + resource.getSpec().to);
    removeWatch(resource, watchersTo);
  }

  private void removeWatch(final ValueInjector resource, final Map<ValueInjector, Watch> map) {
    ofNullable(map.remove(resource))
        .ifPresentOrElse(
            w -> {
              LOGGER.info("Watch stopped");
              w.close();
            },
            () -> LOGGER.info("No watch"));
  }
}
