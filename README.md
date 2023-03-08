# The Value Injector Operator

This Kubernetes operator can monitor a resource and inject values from it into another resource. The transformation from one to the other is done through a [MongoDB aggregation pipeline](https://www.mongodb.com/docs/manual/aggregation/#std-label-aggregation-pipeline-intro) description. The [supported stages](https://www.javadoc.io/doc/net.pincette/pincette-mongo-streams/latest/net.pincette.mongo.streams/net/pincette/mongo/streams/Pipeline.html) exclude the ones that require access to a MongoDB database or Kafka. In the stages most [MongoDB expressions](https://www.javadoc.io/doc/net.pincette/pincette-mongo/latest/net.pincette.mongo/net/pincette/mongo/Expression.html) are supported. Use the transparent `$trace` operator and stage to debug your pipelines.

In the following example the client certificate and the client key from a [vcluster](https://www.vcluster.com) secret are copied to an [Argo CD cluster secret](https://argo-cd.readthedocs.io/en/stable/operator-manual/declarative-setup/#clusters). This way a vcluster can be provisioned through Argo CD, as well as the applications in it.


```yaml
apiVersion: pincette.net/v1
kind: ValueInjector
metadata:
  name: my-cluster-secret-injector
spec:
  from:
    name: vc-my-cluster
    namespace: my-cluster
    kind: Secret
  to:
    name: my-cluster-secret
    namespace: argocd
    kind: Secret
  pipeline:
    - $set:
        to.data.config:
          $replaceOne:
            input:
              $base64Decode: "$to.data.config"
            find: "(KEY)"
            replacement: "$from.data.client-key"
    - $set:
        to.data.config:
          $base64Encode:
            $replaceOne:
              input: "$to.data.config"
              find: "(CERTIFICATE)"
              replacement: "$from.data.client-certificate"
```

In the `to` field the fields `kind` and `name` are mandatory. In the `from` field only the `kind` field is mandatory, which means that a collection of resources can be monitored. If any of them changes, that change will end up in the target resource.

You can also inject values in a resource that lives in another Kubernetes cluster. In the `to` field you add the URL of the cluster in the `apiServer` field. The secret to access the cluster is referred to with the additional `secretRef` field, which has itself the `name` and `namespace` fields. The secret should have at least the fields `clientCert` and `clientKey`. Optionally it can also have the fields `ca` and `clientKeyAlgorithm`. The default value for the latter is `RSA`.

The preceding example would look like this:

```yaml
apiVersion: pincette.net/v1
kind: ValueInjector
metadata:
  name: my-cluster-secret-injector
spec:
  from:
    name: vc-my-cluster
    namespace: my-cluster
    kind: Secret
  to:
    name: my-cluster-secret
    namespace: argocd
    kind: Secret
    apiServer: https://my-cluster.my-cluster.svc
    secretRef:
      name: my-cluster-secret
      namespace: default      
  pipeline:
    - $set:
        to.data.config:
          $replaceOne:
            input:
              $base64Decode: "$to.data.config"
            find: "(KEY)"
            replacement: "$from.data.client-key"
    - $set:
        to.data.config:
          $base64Encode:
            $replaceOne:
              input: "$to.data.config"
              find: "(CERTIFICATE)"
              replacement: "$from.data.client-certificate"
```

If you use a GitOps tool make sure that the fields you change in the target resource are excluded from difference processing.

Install the operator as follows:

```
kubectl apply -f https://github.com/wdonne/pincette-value-injector/raw/main/manifests/install.yaml
```