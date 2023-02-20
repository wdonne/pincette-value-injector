package net.pincette.vi;

import static io.fabric8.kubernetes.client.Config.autoConfigure;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.Base64.getDecoder;
import static java.util.Base64.getEncoder;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static net.pincette.json.Factory.a;
import static net.pincette.json.Factory.f;
import static net.pincette.json.Factory.o;
import static net.pincette.json.Factory.v;
import static net.pincette.json.JsonUtil.toNative;
import static net.pincette.util.Collections.map;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.ScheduledCompletionStage.runAsyncAfter;
import static net.pincette.util.Util.doUntil;
import static net.pincette.util.Util.tryToDoSilent;
import static net.pincette.util.Util.tryToGet;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.Operator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.json.JsonArray;
import javax.json.JsonValue;
import net.pincette.json.Jackson;
import net.pincette.json.JsonUtil;
import net.pincette.vi.ValueInjectorSpec.FromReference;
import net.pincette.vi.ValueInjectorSpec.ToReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// @EnableKubernetesMockClient(crud = true)
class TestValueInjector {
  private static final KubernetesClient client =
      new KubernetesClientBuilder().withConfig(autoConfigure("play")).build();

  private static CompletableFuture<Boolean> assertChange() {
    final CompletableFuture<Boolean> future = new CompletableFuture<>();

    client
        .secrets()
        .inNamespace("ns2")
        .withName("secret2")
        .watch(
            new Watcher<>() {
              public void eventReceived(final Action action, final Secret resource) {
                if ("value1".equals(decodeValue(resource.getData().get("test1")))
                    && "value1".equals(decodeValue(resource.getData().get("test2")))) {
                  future.complete(true);
                }
              }

              public void onClose(final WatcherException cause) {}
            });

    return future;
  }

  private static FromReference createFromReference(
      final String kind, final String name, final String namespace) {
    final FromReference reference = new FromReference();

    reference.kind = kind;
    reference.name = name;
    reference.namespace = namespace;

    return reference;
  }

  private static void createNamespace(final String name) {
    tryToDoSilent(
        () ->
            client
                .namespaces()
                .resource(
                    new NamespaceBuilder()
                        .withApiVersion("v1")
                        .withNewMetadata()
                        .withName(name)
                        .endMetadata()
                        .build())
                .create());
  }

  private static Secret createSecret(final String name, final Map<String, String> fields) {
    return fields.entrySet().stream()
        .reduce(
            new SecretBuilder().withNewMetadata().withName(name).endMetadata(),
            (b, e) ->
                b.addToData(e.getKey(), getEncoder().encodeToString(e.getValue().getBytes(UTF_8))),
            (b1, b2) -> b1)
        .build();
  }

  private static void createSecret(
      final String name, final String namespace, final Map<String, String> fields) {
    final Secret secret = createSecret(name, fields);

    if (namespace != null) {
      client.secrets().inNamespace(namespace).resource(secret).createOrReplace();
    } else {
      client.secrets().resource(secret).createOrReplace();
    }

    waitForSecret(name, namespace, fields);
  }

  private static ToReference createToReference(
      final String kind, final String name, final String namespace) {
    final ToReference reference = new ToReference();

    reference.kind = kind;
    reference.name = name;
    reference.namespace = namespace;

    return reference;
  }

  private static ValueInjector createValueInjector(
      final String name, final FromReference from, final ToReference to, final JsonArray pipeline) {
    final ValueInjectorSpec spec = new ValueInjectorSpec();
    final ValueInjector valueInjector = new ValueInjector();

    valueInjector.setMetadata(new ObjectMetaBuilder().withName(name).build());
    spec.from = from;
    spec.to = to;
    spec.pipeline = toNative(pipeline);
    valueInjector.setSpec(spec);

    return valueInjector;
  }

  private static void createValueInjector(final ValueInjector valueInjector) {
    client.resources(ValueInjector.class).resource(valueInjector).createOrReplace();
  }

  private static String decodeValue(final String encoded) {
    return new String(getDecoder().decode(encoded), UTF_8);
  }

  @AfterAll
  public static void deleteAll() {
    deleteCustomResource();
    deleteNamespace("ns1");
    deleteNamespace("ns2");
  }

  private static void deleteCustomResource() {
    waitForDeleted(
        client
            .apiextensions()
            .v1()
            .customResourceDefinitions()
            .withName("valueinjectors.pincette.net"));
  }

  private static void deleteNamespace(final String name) {
    waitForDeleted(client.namespaces().withName(name));
  }

  private static void deleteValueInjector(final String name) {
    waitForDeleted(client.resources(ValueInjector.class).withName(name));
  }

  private static void deleteSecret(final String name, final String namespace) {
    waitForDeleted(
        namespace != null
            ? client.secrets().inNamespace(namespace).withName(name)
            : client.secrets().withName(name));
  }

  private static boolean hasFields(final Secret secret, final Map<String, String> fields) {
    return fields.entrySet().stream()
        .allMatch(
            e ->
                ofNullable(secret.getData().get(e.getKey()))
                    .map(TestValueInjector::decodeValue)
                    .filter(v -> v.equals(e.getValue()))
                    .isPresent());
  }

  private static void loadCustomResource() {
    tryToDoSilent(
        () ->
            client
                .apiextensions()
                .v1()
                .customResourceDefinitions()
                .load(
                    ValueInjector.class.getResource(
                        "/META-INF/fabric8/valueinjectors.pincette.net-v1.yml"))
                .create());
  }

  @BeforeAll
  public static void prepare() {
    deleteValueInjector("test");
    deleteSecret("secret1", "ns1");
    deleteSecret("secret2", "ns2");
    loadCustomResource();
    createNamespace("ns1");
    createNamespace("ns2");
    startOperator();
  }

  private static List<ObjectNode> toJackson(final JsonArray pipeline) {
    return pipeline.stream()
        .filter(JsonUtil::isObject)
        .map(JsonValue::asJsonObject)
        .map(Jackson::from)
        .collect(toList());
  }

  private static <T> void waitForDeleted(final Resource<T> resource) {
    resource.delete();
    doUntil(
        () -> tryToGet(() -> resource.fromServer().get() == null, e -> true).orElse(true),
        ofMillis(100));
  }

  private static void waitForSecret(
      final String name, final String namespace, final Map<String, String> fields) {
    doUntil(
        () ->
            hasFields(
                namespace != null
                    ? client.secrets().inNamespace(namespace).withName(name).get()
                    : client.secrets().withName(name).get(),
                fields),
        ofMillis(100));
  }

  @Test
  @DisplayName("any name")
  void anyName() {
    modifyLater(null, "ns1");
  }

  @Test
  @DisplayName("any name and any namespace")
  void anyNameAndAnyNamespace() {
    modifyLater(null, null);
  }

  @Test
  @DisplayName("any namespace")
  void anyNamespace() {
    createAfter(null);
  }

  @Test
  @DisplayName("create after")
  void createAfter() {
    createAfter("ns1");
  }

  void createAfter(final String fromNamespace) {
    valueInjector("secret1", fromNamespace);
    createSecret("secret2", "ns2", map(pair("test1", "value2"), pair("test2", "value2")));
    createSecret("secret1", "ns1", map(pair("test1", "value1"), pair("test2", "value1")));
    assertChange().thenAccept(Assertions::assertTrue).join();
  }

  @Test
  @DisplayName("create before")
  void createBefore() {
    valueInjector("secret1", "ns1");
    createSecret("secret1", "ns1", map(pair("test1", "value1"), pair("test2", "value1")));
    createSecret("secret2", "ns2", map(pair("test1", "value2"), pair("test2", "value2")));
    assertChange().thenAccept(Assertions::assertTrue).join();
  }

  private static void startOperator() {
    final Operator operator = new Operator(client);

    operator.register(new ValueInjectorReconciler(client));
    operator.start();
  }

  private static void valueInjector(final String fromName, final String fromNamespace) {
    createValueInjector(
        createValueInjector(
            "test",
            createFromReference("Secret", fromName, fromNamespace),
            createToReference("Secret", "secret2", "ns2"),
            a(
                o(
                    f(
                        "$set",
                        o(
                            f("to.data.test1", v("$from.data.test1")),
                            f("to.data.test2", v("$from.data.test2"))))))));
  }

  @BeforeEach
  public void deleteValueInjector() {
    deleteValueInjector("test");
  }

  @Test
  @DisplayName("modify")
  void modify() {
    valueInjector("secret1", "ns1");
    createSecret("secret1", "ns1", map(pair("test1", "value2"), pair("test2", "value2")));
    createSecret("secret2", "ns2", map(pair("test1", "value2"), pair("test2", "value2")));
    createSecret("secret1", "ns1", map(pair("test1", "value1"), pair("test2", "value1")));
    assertChange().thenAccept(Assertions::assertTrue).join();
  }

  @Test
  @DisplayName("modify later")
  void modifyLater() {
    modifyLater("secret1", "ns1");
  }

  void modifyLater(final String fromName, final String fromNamespace) {
    valueInjector(fromName, fromNamespace);
    createSecret("secret1", "ns1", map(pair("test1", "value2"), pair("test2", "value2")));
    createSecret("secret2", "ns2", map(pair("test1", "value2"), pair("test2", "value2")));
    runAsyncAfter(
        () -> createSecret("secret1", "ns1", map(pair("test1", "value1"), pair("test2", "value1"))),
        ofSeconds(5));
    assertChange().thenAccept(Assertions::assertTrue).join();
  }

  @Test
  @DisplayName("recreate")
  void recreate() {
    valueInjector("secret1", "ns1");
    createSecret("secret1", "ns1", map(pair("test1", "value1"), pair("test2", "value1")));
    createSecret("secret2", "ns2", map(pair("test1", "value2"), pair("test2", "value2")));
    runAsyncAfter(() -> deleteSecret("secret2", "ns2"), ofSeconds(5))
        .thenComposeAsync(
            r ->
                runAsyncAfter(
                    () ->
                        createSecret(
                            "secret2",
                            "ns2",
                            map(pair("test1", "value2"), pair("test2", "value2"))),
                    ofSeconds(5)));
    assertChange().thenAccept(Assertions::assertTrue).join();
  }
}
