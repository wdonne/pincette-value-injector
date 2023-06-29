package net.pincette.vi;

import static io.fabric8.kubernetes.client.Watcher.Action.ADDED;
import static io.fabric8.kubernetes.client.Watcher.Action.MODIFIED;
import static io.fabric8.kubernetes.client.dsl.base.PatchType.SERVER_SIDE_APPLY;
import static io.javaoperatorsdk.operator.api.reconciler.DeleteControl.defaultDelete;
import static io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer.generateNameFor;
import static io.javaoperatorsdk.operator.api.reconciler.UpdateControl.patchStatus;
import static java.lang.Integer.MAX_VALUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getDecoder;
import static java.util.Optional.ofNullable;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Logger.getLogger;
import static net.pincette.json.Jackson.to;
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
import static net.pincette.util.Util.tryToGet;
import static net.pincette.vi.Phase.Ready;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.ConfigFluentImpl;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
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
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.timer.TimerEventSource;
import io.javaoperatorsdk.operator.processing.retry.GradualRetry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow.Processor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.json.JsonObject;
import javax.json.JsonValue;
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
public class ValueInjectorReconciler
    implements Reconciler<ValueInjector>,
        Cleaner<ValueInjector>,
        EventSourceInitializer<ValueInjector> {
  private static final String CA = "ca";
  private static final String CLIENT_CERT = "clientCert";
  private static final String CLIENT_KEY = "clientKey";
  private static final String CLIENT_KEY_ALGORITHM = "clientKeyAlgorithm";
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
  private final TimerEventSource<ValueInjector> timerEventSource = new TimerEventSource<>();
  private final Map<ValueInjector, Watch> watchersFrom = new HashMap<>();
  private final Map<ValueInjector, Watch> watchersTo = new HashMap<>();

  ValueInjectorReconciler(final KubernetesClient client) {
    this.client = client;
  }

  private static void apply(
      final JsonObject json,
      final ValueInjector resource,
      final KubernetesClient client,
      final Consumer<Exception> onError) {
    final String asText = string(json);
    final ToReference reference = resource.getSpec().to;

    LOGGER.info(() -> "Applying " + asText + " to " + reference);

    tryToDo(
        () ->
            genericClient(reference.kind, reference.namespace, reference.apiVersion, client)
                .inNamespace(reference.namespace)
                .withName(reference.name)
                .patch(PATCH_CONTEXT, asText),
        onError);
  }

  private static BiConsumer<GenericKubernetesResource, GenericKubernetesResource> applyChange(
      final ValueInjector resource,
      final KubernetesClient client,
      final Consumer<Exception> onError) {
    final DequePublisher<JsonObject> publisher = dequePublisher();
    final KubernetesClient realClient = getClient(resource, client);

    with(publisher)
        .map(pipeline(resource))
        .map(json -> json.getJsonObject(TO))
        .get()
        .subscribe(
            lambdaSubscriber(json -> apply(fromMongoDB(json), resource, realClient, onError)));

    return (from, to) -> {
      LOGGER.info(() -> "Received resource " + from);

      tryToGet(
              () -> string(toJson(from)),
              e -> {
                logException(e);
                return null;
              })
          .map(json -> pair(json, createMessage(from, to)))
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

  private static JsonObject createMessage(
      final GenericKubernetesResource from, final GenericKubernetesResource to) {
    return toMongoDB(createObjectBuilder().add(FROM, toJson(from)).add(TO, toJson(to)).build());
  }

  private static String decodeValue(final String encoded) {
    return new String(getDecoder().decode(encoded), UTF_8);
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

  private static KubernetesClient getClient(
      final ValueInjector resource, final KubernetesClient client) {
    return ofNullable(resource.getSpec().to.apiServer)
        .flatMap(
            a ->
                ofNullable(resource.getSpec().to.secretRef)
                    .map(
                        ref -> client.secrets().inNamespace(ref.namespace).withName(ref.name).get())
                    .map(s -> pair(a, s.getData())))
        .map(
            pair ->
                new KubernetesClientBuilder()
                    .withConfig(getClientConfig(pair.first, pair.second))
                    .build())
        .orElse(client);
  }

  private static Config getClientConfig(final String apiServer, final Map<String, String> secret) {
    return ImmutableBuilder.create(ConfigBuilder::new)
        .update(b -> b.withMasterUrl(apiServer))
        .update(b -> b.withClientCertData(secret.get(CLIENT_CERT)))
        .update(b -> b.withClientKeyData(secret.get(CLIENT_KEY)))
        .update(ConfigFluentImpl::withDisableHostnameVerification)
        .updateIf(
            () ->
                ofNullable(secret.get(CLIENT_KEY_ALGORITHM))
                    .map(ValueInjectorReconciler::decodeValue),
            ConfigFluentImpl::withClientKeyAlgo)
        .updateIf(() -> ofNullable(secret.get(CA)), ConfigFluentImpl::withCaCertData)
        .build()
        .build();
  }

  private static Optional<GenericKubernetesResource> getResource(
      final FromReference reference, final KubernetesClient client) {
    return getResource(
        reference.kind, reference.name, reference.namespace, reference.apiVersion, client);
  }

  private static Optional<GenericKubernetesResource> getResource(
      final ToReference reference, final KubernetesClient client) {
    return getResource(
        reference.kind, reference.name, reference.namespace, reference.apiVersion, client);
  }

  private static Optional<GenericKubernetesResource> getResource(
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

    return ofNullable(result);
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

  private static String notExists(final Object subject) {
    return subject + " does not exist.";
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

  private static void removeWatch(
      final ValueInjector resource, final Map<ValueInjector, Watch> map) {
    ofNullable(map.remove(resource))
        .ifPresentOrElse(
            w -> {
              LOGGER.info("Watch stopped");
              w.close();
            },
            () -> LOGGER.info("No watch"));
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
    return to((ObjectNode) MAPPER.valueToTree(resource));
  }

  private static void updateStatus(
      final ValueInjector resource,
      final ValueInjectorStatus status,
      final KubernetesClient client) {
    Util.with(
        () -> valueInjectorClient(client),
        c -> {
          final ValueInjector loaded = c.resource(resource).get();

          loaded.setStatus(status);
          c.resource(loaded).patchStatus();

          return loaded;
        });
  }

  private static MixedOperation<
          ValueInjector, KubernetesResourceList<ValueInjector>, Resource<ValueInjector>>
      valueInjectorClient(final KubernetesClient client) {
    return client.resources(ValueInjector.class);
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

  private static <T> Watcher<T> watcher(
      final BiConsumer<Action, T> fn, final Consumer<Exception> onError) {
    return new Watcher<>() {
      private boolean closed;

      public void eventReceived(final Action action, final T resource) {
        tryToDo(
            () -> {
              if (!closed) {
                fn.accept(action, resource);
              }
            },
            onError);
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

  private UpdateControl<ValueInjector> error(final ValueInjector resource, final Throwable t) {
    return error(resource, t.getMessage());
  }

  private UpdateControl<ValueInjector> error(final ValueInjector resource, final String message) {
    retry(resource);
    resource.setStatus(new ValueInjectorStatus(message));

    return patchStatus(resource);
  }

  private Watch fromWatcher(
      final ValueInjector resource,
      final BiConsumer<GenericKubernetesResource, GenericKubernetesResource> applyChange,
      final KubernetesClient client) {
    final ToReference to = resource.getSpec().to;

    return watchable(resource.getSpec().from, client)
        .watch(
            watcher(
                handleEvent(
                    set(ADDED, MODIFIED),
                    fromRes ->
                        getResource(to, getClient(resource, client))
                            .ifPresentOrElse(
                                t -> applyChange.accept(fromRes, t),
                                () -> {
                                  updateStatus(
                                      resource, new ValueInjectorStatus(notExists(to)), client);
                                  retry(resource);
                                })),
                e -> retryAtException(resource, e)));
  }

  public Map<String, EventSource> prepareEventSources(
      final EventSourceContext<ValueInjector> context) {
    timerEventSource.start();

    return Collections.map(pair(generateNameFor(timerEventSource), timerEventSource));
  }

  public UpdateControl<ValueInjector> reconcile(
      final ValueInjector resource, final Context<ValueInjector> context) {
    return tryToGet(
            () -> {
              final BiConsumer<GenericKubernetesResource, GenericKubernetesResource> applyChange =
                  applyChange(resource, client, e -> retryAtException(resource, e));
              final FromReference from = resource.getSpec().from;

              removeWatch(resource);
              watchersFrom.put(resource, fromWatcher(resource, applyChange, client));

              resource.setStatus(
                  ofNullable(from.name)
                      .map(name -> runToSide(resource, applyChange))
                      .orElseGet(ValueInjectorStatus::new));

              if (resource.getStatus().phase != Ready) {
                retry(resource);
              }

              return patchStatus(resource);
            },
            e -> error(resource, e))
        .orElseGet(UpdateControl::noUpdate);
  }

  private void removeWatch(final ValueInjector resource) {
    LOGGER.info(() -> "Stopping watch for " + resource.getSpec().from);
    removeWatch(resource, watchersFrom);
    LOGGER.info(() -> "Stopping watch for " + resource.getSpec().to);
    removeWatch(resource, watchersTo);
  }

  private void retry(final ValueInjector resource) {
    timerEventSource.scheduleOnce(resource, 5000);
  }

  private void retryAtException(final ValueInjector resource, final Throwable t) {
    LOGGER.severe(t.getMessage());
    updateStatus(resource, new ValueInjectorStatus(t.getMessage()), client);
    retry(resource);
  }

  private ValueInjectorStatus runToSide(
      final ValueInjector resource,
      final BiConsumer<GenericKubernetesResource, GenericKubernetesResource> applyChange) {
    final FromReference from = resource.getSpec().from;
    final ToReference to = resource.getSpec().to;

    return getResource(from, client)
        .map(
            f -> {
              watchersTo.put(resource, toWatcher(resource, applyChange, client));

              return getResource(to, getClient(resource, client))
                  .map(
                      t -> {
                        applyChange.accept(f, t);

                        return new ValueInjectorStatus();
                      })
                  .orElseGet(() -> new ValueInjectorStatus(notExists(to)));
            })
        .orElseGet(() -> new ValueInjectorStatus(notExists(from)));
  }

  private Watch toWatcher(
      final ValueInjector resource,
      final BiConsumer<GenericKubernetesResource, GenericKubernetesResource> applyChange,
      final KubernetesClient client) {
    final FromReference from = resource.getSpec().from;

    return watchable(resource.getSpec().to, getClient(resource, client))
        .watch(
            watcher(
                handleEvent(
                    set(ADDED, MODIFIED),
                    toRes -> {
                      if (!isInjected(toRes)) {
                        getResource(from, client)
                            .ifPresentOrElse(
                                f -> applyChange.accept(f, toRes),
                                () -> {
                                  updateStatus(
                                      resource, new ValueInjectorStatus(notExists(from)), client);
                                  retry(resource);
                                });
                      }
                    }),
                e -> retryAtException(resource, e)));
  }
}
