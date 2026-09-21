# JAVA Orchesty Camel Adapter

## How to use ?
- Install the package `io.orchesty:orchesty-java-camel-adapter`, next to the [Orchesty Java SDK](https://github.com/Orchesty/orchesty-java-sdk) (`io.orchesty:orchesty-java-sdk`)
- Add the Camel component jars you register, e.g. `org.apache.camel:camel-ftp` or `org.apache.camel:camel-file`; the adapter ships only the Camel engine (`camel-core-engine`, `camel-support`, `camel-core-languages`, `camel-bean`) and `camel-catalog`, use Camel 4.20+ (built against 4.22)
- Import the classes ``import io.orchesty.sdk.camel.CamelRuntime;``, ``import static io.orchesty.sdk.camel.metadata.CamelComponentRegistrar.registerComponent;``
- First you will need to create the Camel runtime by calling `CamelRuntime camel = new CamelRuntime();`
- Then register the components into the SDK's `NodeRegistry`
  - Example: `registerComponent(registry, camel, "sftp");`
  - `registerComponent` fills name, title, form and endpoint URI from the Camel Catalog
- Finally, start the server as with the SDK alone: `new WorkerServer(WorkerConfig.fromEnv(), registry).start();`

## How to develop
1. Run `make init` for start dev environment
2. Tests can be run by `make test` or `make fasttest`

## Registering components

Camel is used only as an **in-process producer / polling library**; Orchesty owns
orchestration, retry and observability. Forms come from Catalog metadata, the component jar is needed
at runtime when the node actually runs. A retryable failure is thrown as `OnRepeatException`, as in
the SDK, so a repeater enabled on the node in Orchesty wins over the registration's retry interval
and hops.

```java
CamelRuntime camel = new CamelRuntime();

registerComponent(registry, camel, "sftp");

registerComponent(registry, camel, "file",
        ComponentOptions.source().emit(ACamelPollingBatch.EmitMode.ITEM).build());

registerComponent(registry, camel, "google-sheets-stream",
        ComponentOptions.source()
                .sourceFactory(MySheetsBatch::new) // your ACamelPollingBatch subclass
                .oauth2(authUrl, tokenUrl, List.of(scope))
                .includeForm("spreadsheetId", "range")
                .build());

registerKamelet(registry, camel, "uppercase-action");
```

`ComponentOptions` is only for deviations: historical node names, a form subset,
OAuth authorize/token URLs (not in the Catalog), emit mode, batch size, poll timeout,
the poll path (`scheduled()` / `onDemand()`), payload allow-lists (`payloadOptions()` /
`payloadHeaders()`), or a specialized batch (`sourceFactory`). The adapter ships only generic
nodes; a component that needs custom paging or item mapping gets its own `ACamelPollingBatch`
subclass in the worker. The demo worker's `GoogleSheetsSourceBatch` is a complete example.
Kamelets are loaded from `kamelets/<name>.kamelet.yaml` on the classpath.

A subclass builds its endpoint URI only with `EndpointUriBuilder`: values from the install, the
payload or the batch cursor go into the checked `options`, the subclass's own constants into
`trustedDefaults` (`connectorUri(…)` / `sourceUri(…)`). Call `UntrustedValues.check` on payload
values used anywhere else. A hand-written URI string bypasses every check.

HTTP connectors belong on the SDK (`HttpSender` / `AConnector`), not this adapter. The generic
connector has no HTTP method shortcut; it only classifies a response status when a component sets
`CamelHttpResponseCode`.

### Payload options

Credentials and connection settings come from the application install. A message may only
**add** endpoint options the install leaves unset, and only the ones the registration allows:

```java
registerComponent(registry, camel, "sftp",
        ComponentOptions.builder().payloadOptions("fileName").payloadHeaders("CamelFileName").build());
```

- connectors and sources read them from `endpointOptions`; sources also from top-level path
  parameters such as `directoryName`. Other top-level keys of a source payload are ordinary
  message data and are ignored;
- the component must declare the option and secret options are never accepted;
- `Camel*` headers in a connector's `headers` map are dropped unless listed in `payloadHeaders`,
  because many components let them override endpoint settings;
- `kameletParameters` may set any parameter the Kamelet declares.

No value from the install or the payload may contain a placeholder or expression (`{{`, `${`,
`$simple{`) or start with a Camel reference or raw marker (`#bean:`, `#class:`, `#type:`,
`#property:`, `#valueAs(`, `#autowired`, `RAW(`, `RAW{`): Camel would resolve them in the worker
that all tenants share. A plain `#…` value such as the password `#secret1` or the channel
`#general` is fine. Developer-set `uriDefault`s skip this check, so they may use references such as
`#bean:dataSource`. For every value, path values (`host`, `directoryName`, …) must not contain `?`
or `&`, secrets must not contain `)&`, and the built URI is parsed back and must carry exactly the
intended options. A Kamelet parameter that ends up in an endpoint URI (outside an expression, or
in the expression of `enrich`, `pollEnrich`, `recipientList`, `routingSlip` or `dynamicRouter`)
must not contain `?`, `&` or a percent-escape such as `%3F`, because Camel substitutes it as text
and decodes the URI. One that a recipient list, routing slip or dynamic router splits into several
URIs may contain only letters, digits and `. _ ~ - / : @`. A template that builds URIs at runtime
(`toD`, `${...}` in a URI, or one of those processors) could move any parameter into a URI through
a header, so then these rules apply to every parameter it declares.

A rejected option stops the message instead of retrying it. The same lists exist in descriptor
YAML (`payloadOptions: [...]`, `payloadHeaders: [...]`).

### Sources

Sources poll on demand on the shared component only for `file`, `ftp`, `ftps` and `sftp`, which
keep all credentials in the endpoint URI. They use one polling consumer per batch, so a remote
source logs in once per batch. Every other source (including `sql`) is drained by one scheduled
poll on a private component, limited to the batch size where the component allows it
(`maxResults` / `maxMessagesPerPoll`; registration warns when it can't be limited). `onDemand()`
forces the shared path; use it only for components that cache no tenant credentials on the
component.

Both paths map each item and then commit it (for example, mail is marked as seen or a file is
moved) while the poll runs, so an item is emitted at most once. An item that fails or cannot be
mapped is rolled back and stays in the source for the next poll. A scheduled poll that runs past
its timeout is stopped; the items it committed are still emitted, so the result can be a partial
page. A timed-out poll that mapped nothing is retried.

The `file` component reads and writes the worker's own file system, so register it only on
workers that serve a single tenant. Set `moveFailed` (as a `uriDefault`) on on-demand file
sources: a file that keeps failing is otherwise picked first on every poll and blocks the
directory.

Connectors to remote file servers disconnect after each send (`disconnect=true`).

### Kamelets

Every distinct set of Kamelet parameters becomes one route in the Camel context. The runtime keeps
at most 200 of them (`CamelRuntime.setMaxKameletRoutes`) and removes the least recently used.

The endpoints inside a Kamelet route run on the context's shared components, so all tenants share
them. Register only Kamelets whose components cache no tenant client or credentials on the
component.

## Tenant isolation

Some Camel components cache an authenticated client on the component instance (for example
the Google Sheets components), so one shared component would hand one tenant's client to the next.
The adapter therefore never shares such a component between tenants:

- connectors get one private component instance per tenant and credential set, reused for that
  tenant (at most 100 by default, see `CamelRuntime.setMaxTenantComponents`) and stopped once
  evicted and idle;
- scheduled sources get a fresh private component for every poll.

Private components are never registered in the Camel context, so components keep their real URI
scheme. On-demand sources (see above) and Kamelets use the shared components.
